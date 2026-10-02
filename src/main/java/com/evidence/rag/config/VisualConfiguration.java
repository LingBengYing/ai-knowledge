package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.VisualAnswerService;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit local opt-in. Creates clients but never submits model or index requests at startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.visual", name = "enabled", havingValue = "true")
public class VisualConfiguration {
  @Bean(destroyMethod = "close")
  OpenAiCompatibleVisionModels visionModels(Environment environment) {
    try {
      String address = environment.getProperty("server.address");
      String mode = environment.getProperty("rag.environment", "development");
      if (!("127.0.0.1".equals(address) || "::1".equals(address))
          || !("development".equals(mode) || "test".equals(mode))
          || !environment.getProperty("rag.answers.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException();
      }
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create(environment.getRequiredProperty("rag.visual.base-url")),
              environment.getRequiredProperty("rag.visual.model"),
              environment.getRequiredProperty("rag.visual.api-key"));
      return new OpenAiCompatibleVisionModels(
          new OpenAiCompatibleVisionModels.Configuration(
              endpoint,
              Duration.ofMillis(
                  environment.getProperty("rag.visual.deadline-ms", Long.class, 30000L)),
              environment.getProperty("rag.visual.max-response-bytes", Integer.class, 262144),
              environment.getProperty("rag.visual.allow-loopback-http", Boolean.class, false)));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid local visual model configuration");
    }
  }

  @Bean
  VisualIngestionOptions visualIngestionOptions(VisionModels models) {
    return new VisualIngestionOptions(models.revision());
  }

  @Bean(destroyMethod = "close")
  VisualAnswerService visualAnswerService(
      EvidenceService evidence,
      TextModels text,
      VisionModels vision,
      RetrievalProjection projection,
      TextAdapterSettings settings,
      AnswersSettings limits,
      ObjectProvider<QueryAttachmentService> queryAttachments) {
    var target =
        new IndexTarget(
            settings.projection().embeddingIdentity(),
            settings.projection().identity(),
            text.revision(),
            settings.projection().dimension());
    return new VisualAnswerService(
        evidence,
        text,
        vision,
        projection,
        target,
        Duration.ofMillis(limits.timeoutMs()),
        limits.maxConcurrent(),
        queryAttachments.getIfAvailable());
  }
}
