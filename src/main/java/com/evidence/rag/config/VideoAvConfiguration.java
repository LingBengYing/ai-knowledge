package com.evidence.rag.config;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.model.GeminiVideoAvModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.VideoAvAnswerService;
import com.evidence.rag.service.VideoAvCompilationService;
import com.evidence.rag.service.VideoAvLibraryService;
import com.evidence.rag.web.BoundedMediaQueryServlet;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.web.VideoAvUploadServlet;
import com.evidence.rag.web.converter.VideoAvQueryRequestMapper;
import com.evidence.rag.worker.parser.ProcessVideoAvDecoder;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** Independent opt-in video audiovisual graph; construction never contacts models or Milvus. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.video-av", name = "enabled", havingValue = "true")
public class VideoAvConfiguration {
  @Bean(destroyMethod = "close")
  VideoAvCompilationService videoAvCompilationService(Environment environment) {
    try {
      requireLocal(environment);
      int chunk = environment.getProperty("rag.video-av.chunk-seconds", Integer.class, 30);
      var decoder =
          new ProcessVideoAvDecoder(
              Path.of(environment.getRequiredProperty("rag.video-av.ffmpeg-executable")),
              Path.of(environment.getRequiredProperty("rag.video-av.ffprobe-executable")),
              duration(environment, "rag.video-av.decode-deadline-ms", 30000),
              chunk);
      try {
        if (!decoder
            .revision()
            .equals(environment.getRequiredProperty("rag.video-av.decoder-revision"))) {
          throw invalid();
        }
        return new VideoAvCompilationService(
            decoder, chunk, duration(environment, "rag.video-av.compilation-budget-ms", 120000));
      } catch (RuntimeException failure) {
        decoder.close();
        throw failure;
      }
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean
  VideoAvSettings videoAvSettings(
      Environment environment,
      RagProperties properties,
      VideoAvCompilationService compilation,
      ObjectProvider<TextAdapterSettings> text,
      ObjectProvider<ImageEmbeddingSettings> images,
      ObjectProvider<AudioEmbeddingSettings> audio,
      ObjectProvider<SoundSettings> sound) {
    try {
      requireLocal(environment);
      String prefix = "rag.video-av.";
      var models =
          new GeminiVideoAvModels.Configuration(
              endpoint(environment, prefix),
              environment.getRequiredProperty(prefix + "revision"),
              duration(environment, prefix + "deadline-ms", 30000),
              environment.getProperty(prefix + "max-response-bytes", Integer.class, 4194304),
              environment.getProperty(prefix + "allow-loopback-http", Boolean.class, false));
      String embed = prefix + "embedding.";
      var embedding =
          new GeminiVideoAvEmbeddingModels.Configuration(
              endpoint(environment, embed),
              environment.getRequiredProperty(embed + "revision"),
              environment.getRequiredProperty(embed + "dimensions", Integer.class),
              environment.getRequiredProperty(prefix + "decoder-revision"),
              duration(environment, embed + "deadline-ms", 30000),
              environment.getProperty(embed + "max-response-bytes", Integer.class, 4194304),
              environment.getProperty(embed + "allow-loopback-http", Boolean.class, false));
      if (!embedding.decoderRevision().equals(compilation.decoderRevision())
          || !compilation.configurationCurrent()) {
        throw invalid();
      }
      var collections = new HashSet<String>();
      var textSettings = text.getIfAvailable();
      var imageSettings = images.getIfAvailable();
      var audioSettings = audio.getIfAvailable();
      var soundSettings = sound.getIfAvailable();
      if (textSettings != null) {
        collections.add(textSettings.projection().collection());
      }
      if (imageSettings != null) {
        collections.add(imageSettings.projection().collection());
      }
      if (audioSettings != null) {
        collections.add(audioSettings.projection().collection());
      }
      if (soundSettings != null) {
        collections.add(soundSettings.projection().collection());
      }
      String visualCollection = environment.getRequiredProperty(prefix + "milvus.video-collection");
      String audioCollection = environment.getRequiredProperty(prefix + "milvus.audio-collection");
      if (!collections.add(visualCollection) || !collections.add(audioCollection)) {
        throw invalid();
      }
      var visualProjection = projection(environment, properties, embedding, visualCollection);
      var audioProjection = projection(environment, properties, embedding, audioCollection);
      var targets =
          new VideoAvTargets(
              target(embedding, visualProjection), target(embedding, audioProjection));
      return new VideoAvSettings(
          models,
          embedding,
          visualProjection,
          audioProjection,
          targets,
          duration(environment, prefix + "processing-timeout-ms", 120000),
          environment.getProperty(prefix + "max-concurrent", Integer.class, 2));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean(destroyMethod = "close")
  GeminiVideoAvModels videoAvModels(VideoAvSettings settings) {
    return new GeminiVideoAvModels(settings.models());
  }

  @Bean(destroyMethod = "close")
  GeminiVideoAvEmbeddingModels videoAvEmbeddingModels(VideoAvSettings settings) {
    return new GeminiVideoAvEmbeddingModels(settings.embedding());
  }

  @Bean(destroyMethod = "close")
  MilvusRestProjection videoAvVideoProjection(VideoAvSettings settings) {
    return new MilvusRestProjection(settings.visualProjection());
  }

  @Bean(destroyMethod = "close")
  MilvusRestProjection videoAvAudioProjection(VideoAvSettings settings) {
    return new MilvusRestProjection(settings.audioProjection());
  }

  @Bean
  VideoAvTargets videoAvTargets(VideoAvSettings settings) {
    return settings.targets();
  }

  @Bean(destroyMethod = "close")
  VideoAvLibraryService videoAvLibraryService(
      SqliteAuthorityStore store,
      VideoAvRepository repository,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      VideoAvCompilationService compilation,
      VideoAvSettings settings,
      Environment environment) {
    var service =
        new VideoAvLibraryService(
            store,
            repository,
            management,
            permissions,
            compilation,
            settings.targets(),
            settings.models().revision(),
            settings.embedding(),
            settings.visualProjection(),
            settings.audioProjection(),
            settings.processingBudget(),
            settings.maxConcurrent());
    service.setAutomaticIndexingEnabled(
        environment.getProperty("rag.import-auto-index.enabled", Boolean.class, true));
    return service;
  }

  @Bean(destroyMethod = "close")
  VideoAvAnswerService videoAvAnswerService(
      SqliteAuthorityStore store,
      VideoAvRepository repository,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      VideoAvCompilationService compilation,
      GeminiVideoAvModels models,
      GeminiVideoAvEmbeddingModels embedding,
      @Qualifier("videoAvVideoProjection") MilvusRestProjection visualProjection,
      @Qualifier("videoAvAudioProjection") MilvusRestProjection audioProjection,
      VideoAvSettings settings,
      VideoAvLibraryService library) {
    return new VideoAvAnswerService(
        store,
        repository,
        management,
        permissions,
        compilation,
        models,
        embedding,
        visualProjection,
        audioProjection,
        settings.targets(),
        library.profileFingerprint(),
        settings.processingBudget(),
        settings.maxConcurrent());
  }

  @Bean
  ServletRegistrationBean<VideoAvUploadServlet> videoAvUploadServlet(
      VideoAvLibraryService library, JsonMapper json, ProblemHandler errors) {
    var registration =
        new ServletRegistrationBean<>(
            new VideoAvUploadServlet(library, json, errors), "/v1/video-av-documents");
    registration.setName("videoAvUploadServlet");
    registration.setAsyncSupported(true);
    return registration;
  }

  @Bean
  ServletRegistrationBean<BoundedMediaQueryServlet> videoAvQueryServlet(
      VideoAvAnswerService answers,
      VideoAvSettings settings,
      JsonMapper json,
      ProblemHandler errors) {
    var registration =
        new ServletRegistrationBean<>(
            new BoundedMediaQueryServlet(
                (actor, body) -> {
                  var command = VideoAvQueryRequestMapper.attached(body);
                  return answers.answerAttached(actor, command.answer(), command.attachments());
                },
                VideoAvQueryRequestMapper.MAX_REQUEST_BYTES,
                30000,
                Math.toIntExact(settings.processingBudget().toMillis()),
                settings.maxConcurrent(),
                json,
                errors),
            "/v1/video-av-query-answers");
    registration.setName("videoAvQueryServlet");
    registration.setAsyncSupported(true);
    return registration;
  }

  private static MilvusRestProjection.Settings projection(
      Environment environment,
      RagProperties properties,
      GeminiVideoAvEmbeddingModels.Configuration embedding,
      String collection) {
    String prefix = "rag.video-av.milvus.";
    return new MilvusRestProjection.Settings(
        URI.create(environment.getRequiredProperty(prefix + "endpoint")),
        environment.getProperty(prefix + "token", ""),
        environment.getProperty(prefix + "database", "default"),
        collection,
        properties.workspaceId(),
        embedding.revision(),
        embedding.dimensions(),
        duration(environment, prefix + "deadline-ms", 30000),
        environment.getProperty(prefix + "max-response-bytes", Integer.class, 4194304),
        environment.getProperty(prefix + "allow-loopback-http", Boolean.class, false));
  }

  private static IndexTarget target(
      GeminiVideoAvEmbeddingModels.Configuration embedding,
      MilvusRestProjection.Settings projection) {
    return new IndexTarget(
        embedding.revision(), projection.identity(), embedding.revision(), embedding.dimensions());
  }

  private static Endpoint endpoint(Environment environment, String prefix) {
    return new Endpoint(
        URI.create(environment.getRequiredProperty(prefix + "base-url")),
        environment.getRequiredProperty(prefix + "model"),
        environment.getRequiredProperty(prefix + "api-key"));
  }

  private static Duration duration(Environment environment, String property, long fallback) {
    return Duration.ofMillis(environment.getProperty(property, Long.class, fallback));
  }

  private static void requireLocal(Environment environment) {
    String address = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!(Objects.equals(address, "127.0.0.1") || Objects.equals(address, "::1"))
        || !(mode.equals("development") || mode.equals("test"))
        || !environment.getProperty("rag.ingestion.enabled", Boolean.class, false)) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid local video audiovisual configuration");
  }
}
