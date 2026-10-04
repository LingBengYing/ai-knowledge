package com.evidence.rag.config;

import com.evidence.rag.client.model.AudioEmbeddingModels;
import com.evidence.rag.client.model.ImageEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleQueryRankingModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.QueryPreparationService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.service.VisualAnswerService;
import com.evidence.rag.web.BoundedMediaQueryServlet;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.web.converter.QueryAttachmentRequestMapper;
import com.evidence.rag.worker.parser.ProcessImageParser;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local attachment opt-in without startup model requests. */
@Configuration(proxyBeanMethods = false)
@Conditional(MediaModulesCondition.class)
@ConditionalOnProperty(prefix = "rag.query-attachments", name = "enabled", havingValue = "true")
@ConditionalOnExpression("!${rag.video.text-evidence-only:false}")
@EnableConfigurationProperties(QueryAttachmentSettings.class)
public class QueryAttachmentConfiguration {
  @Bean(destroyMethod = "close")
  OpenAiCompatibleQueryRankingModels queryRankingModels(Environment environment) {
    try {
      requireLocal(environment);
      return new OpenAiCompatibleQueryRankingModels(
          new OpenAiCompatibleQueryRankingModels.Configuration(
              new OpenAiCompatibleModels.Endpoint(
                  URI.create(
                      environment.getRequiredProperty("rag.query-attachments.ranking.base-url")),
                  environment.getRequiredProperty("rag.query-attachments.ranking.model"),
                  environment.getRequiredProperty("rag.query-attachments.ranking.api-key")),
              Duration.ofMillis(
                  environment.getProperty(
                      "rag.query-attachments.ranking.deadline-ms", Long.class, 30000L)),
              environment.getProperty(
                  "rag.query-attachments.ranking.max-response-bytes", Integer.class, 262144),
              environment.getProperty(
                  "rag.query-attachments.ranking.allow-loopback-http", Boolean.class, false)));
    } catch (RuntimeException invalid) {
      throw invalidConfiguration();
    }
  }

  @Bean(destroyMethod = "close")
  ProcessImageParser queryImageOcr(Environment environment) {
    try {
      requireLocal(environment);
      return new ProcessImageParser(
          ImageOcrConfiguration.options(environment),
          Duration.ofMillis(
              environment.getProperty(
                  "rag.query-attachments.ocr-deadline-ms", Long.class, 30000L)));
    } catch (RuntimeException invalid) {
      throw invalidConfiguration();
    }
  }

  @Bean
  QueryPreparationService queryPreparationService(
      VisionModels vision,
      @Qualifier("queryImageOcr") ProcessImageParser ocr,
      AudioCompilationService audio,
      VideoCompilationService video,
      AnswersSettings limits,
      ObjectProvider<AudioEmbeddingModels> audioEmbedding) {
    return new QueryPreparationService(
        vision,
        ocr,
        audio,
        video,
        Duration.ofMillis(limits.timeoutMs()),
        audioEmbedding.getIfAvailable() != null);
  }

  @Bean
  @Conditional(LegacyTextCondition.class)
  QueryAttachmentService queryAttachmentService(
      QueryPreparationService preparation,
      QueryRankingModels ranking,
      TextModels text,
      RetrievalProjection projection,
      TextAdapterSettings settings,
      ObjectProvider<ImageEmbeddingModels> imageModels,
      @Qualifier("imageVectorProjection") ObjectProvider<RetrievalProjection> imageProjection,
      @Qualifier("imageVectorTarget") ObjectProvider<IndexTarget> imageTarget,
      ObjectProvider<AudioEmbeddingModels> audioModels,
      @Qualifier("audioVectorProjection") ObjectProvider<RetrievalProjection> audioProjection,
      @Qualifier("audioVectorTarget") ObjectProvider<IndexTarget> audioTarget) {
    var target =
        new IndexTarget(
            settings.projection().embeddingIdentity(),
            settings.projection().identity(),
            text.revision(),
            settings.projection().dimension());
    return new QueryAttachmentService(
        preparation,
        ranking,
        text,
        projection,
        target,
        imageModels.getIfAvailable(),
        imageProjection.getIfAvailable(),
        imageTarget.getIfAvailable(),
        audioModels.getIfAvailable(),
        audioProjection.getIfAvailable(),
        audioTarget.getIfAvailable());
  }

  @Bean
  ServletRegistrationBean<BoundedMediaQueryServlet> queryAttachmentServlet(
      ObjectProvider<AnswerService> answers,
      ObjectProvider<VisualAnswerService> visual,
      QueryAttachmentSettings transport,
      AnswersSettings processing,
      JsonMapper json,
      ProblemHandler errors, ObjectProvider<ManagedTextRuntime> managed) {
    var registration =
        new ServletRegistrationBean<>(
            new BoundedMediaQueryServlet(
                (actor, body) -> {
                  var command = QueryAttachmentRequestMapper.command(body);
                  var runtime = managed.getIfAvailable();
                  var snapshot = runtime == null ? null : runtime.capture();
                  var currentVisual =
                      snapshot == null ? visual.getIfAvailable() : snapshot.visual();
                  var currentAnswers =
                      snapshot == null ? answers.getIfAvailable() : snapshot.answers();
                  if (command.mode() == QueryAnswerMode.IMAGE) {
                    if (currentVisual == null) {
                      throw new ApplicationException(
                          FailureKind.UNAVAILABLE, "query_attachment_unavailable", "附件提问暂不可用。");
                    }
                    return currentVisual.answerAttached(actor, command.answer(), command.attachments());
                  }
                  if (currentAnswers == null) {
                    throw new ApplicationException(
                        FailureKind.UNAVAILABLE, "query_attachment_unavailable", "附件提问暂不可用。");
                  }
                  return currentAnswers.answerAttached(actor, command);
                },
                QueryAttachmentRequestMapper.MAX_REQUEST_BYTES,
                transport.receiveTimeoutMs(),
                processing.timeoutMs(),
                transport.maxConcurrent(),
                json,
                errors),
            "/v1/attachment-answers");
    registration.setName("queryAttachmentServlet");
    registration.setAsyncSupported(true);
    return registration;
  }

  private static void requireLocal(Environment environment) {
    String address = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(address) || "::1".equals(address))
        || !("development".equals(mode) || "test".equals(mode))) {
      throw invalidConfiguration();
    }
    for (String dependency : new String[] {"answers", "visual", "audio", "video", "image-ocr"}) {
      if (!environment.getProperty("rag." + dependency + ".enabled", Boolean.class, false)) {
        throw invalidConfiguration();
      }
    }
  }

  private static IllegalArgumentException invalidConfiguration() {
    return new IllegalArgumentException("Invalid local query attachment configuration");
  }
}
