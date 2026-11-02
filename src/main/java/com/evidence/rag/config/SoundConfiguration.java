package com.evidence.rag.config;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.SoundCompilationService;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.web.OriginalMediaUploadServlet;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** Explicit independent sound composition; no ASR dependency and no startup network requests. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.sound", name = "enabled", havingValue = "true")
public class SoundConfiguration {
  @Bean(destroyMethod = "close")
  SoundCompilationService soundCompilationService(Environment environment) {
    try {
      requireLocal(environment);
      var decoder =
          new ProcessAudioDecoder(
              Path.of(environment.getRequiredProperty("rag.sound.ffmpeg-executable")),
              Path.of(environment.getRequiredProperty("rag.sound.ffprobe-executable")),
              duration(environment, "rag.sound.decode-deadline-ms", 30000));
      try {
        if (!decoder
            .revision()
            .equals(environment.getRequiredProperty("rag.sound.decoder-revision"))) {
          throw invalid();
        }
        return new SoundCompilationService(
            decoder,
            environment.getProperty("rag.sound.chunk-seconds", Integer.class, 15),
            duration(environment, "rag.sound.compilation-budget-ms", 120000));
      } catch (RuntimeException failure) {
        decoder.close();
        throw failure;
      }
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean
  SoundSettings soundSettings(
      Environment environment,
      RagProperties properties,
      SoundCompilationService compilation,
      ObjectProvider<TextAdapterSettings> text,
      ObjectProvider<ImageEmbeddingSettings> images,
      ObjectProvider<AudioEmbeddingSettings> audio) {
    try {
      requireLocal(environment);
      String prefix = "rag.sound.";
      var models =
          new GeminiSoundModels.Configuration(
              endpoint(environment, prefix),
              environment.getRequiredProperty(prefix + "revision"),
              duration(environment, prefix + "deadline-ms", 30000),
              environment.getProperty(prefix + "max-response-bytes", Integer.class, 4194304),
              environment.getProperty(prefix + "allow-loopback-http", Boolean.class, false));
      String embed = prefix + "embedding.";
      var embedding =
          new GeminiSoundEmbeddingModels.Configuration(
              endpoint(environment, embed),
              environment.getRequiredProperty(embed + "revision"),
              environment.getRequiredProperty(embed + "dimensions", Integer.class),
              environment.getRequiredProperty(prefix + "decoder-revision"),
              duration(environment, embed + "deadline-ms", 30000),
              environment.getProperty(embed + "max-response-bytes", Integer.class, 4194304),
              environment.getProperty(embed + "allow-loopback-http", Boolean.class, false));
      String collection = environment.getRequiredProperty(prefix + "milvus.collection");
      var textSettings = text.getIfAvailable();
      var imageSettings = images.getIfAvailable();
      var audioSettings = audio.getIfAvailable();
      if (!embedding.decoderRevision().equals(compilation.decoderRevision())
          || !compilation.configurationCurrent()
          || textSettings != null && collection.equals(textSettings.projection().collection())
          || imageSettings != null && collection.equals(imageSettings.projection().collection())
          || audioSettings != null && collection.equals(audioSettings.projection().collection())) {
        throw invalid();
      }
      var projection =
          new MilvusRestProjection.Settings(
              URI.create(environment.getRequiredProperty(prefix + "milvus.endpoint")),
              environment.getProperty(prefix + "milvus.token", ""),
              environment.getProperty(prefix + "milvus.database", "default"),
              collection,
              properties.workspaceId(),
              embedding.revision(),
              embedding.dimensions(),
              duration(environment, prefix + "milvus.deadline-ms", 30000),
              environment.getProperty(prefix + "milvus.max-response-bytes", Integer.class, 4194304),
              environment.getProperty(prefix + "milvus.allow-loopback-http", Boolean.class, false));
      var target =
          new IndexTarget(
              embedding.revision(),
              projection.identity(),
              embedding.revision(),
              embedding.dimensions());
      return new SoundSettings(
          models,
          embedding,
          projection,
          target,
          duration(environment, prefix + "processing-timeout-ms", 120000),
          environment.getProperty(prefix + "max-concurrent", Integer.class, 2));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean
  SoundLibraryService soundLibraryService(
      SqliteAuthorityStore store,
      SoundRepository repository,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      SoundCompilationService compilation,
      SoundSettings settings,
      Environment environment) {
    var service =
        new SoundLibraryService(
            store,
            repository,
            management,
            permissions,
            compilation,
            settings.target(),
            settings.models(),
            settings.embedding(),
            settings.projection(),
            settings.processingBudget(),
            settings.maxConcurrent());
    service.setAutomaticIndexingEnabled(
        environment.getProperty("rag.import-auto-index.enabled", Boolean.class, true));
    return service;
  }

  @Bean
  ServletRegistrationBean<OriginalMediaUploadServlet> soundUploadServlet(
      SoundLibraryService library, JsonMapper json, ProblemHandler errors) {
    var registration =
        new ServletRegistrationBean<>(
            OriginalMediaUploadServlet.sound(library, json, errors), "/v1/sound-documents");
    registration.setName("soundUploadServlet");
    registration.setAsyncSupported(true);
    return registration;
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
    if (!RuntimeGuard.isLocalDevelopment(environment, "ingestion")) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid local sound configuration");
  }
}
