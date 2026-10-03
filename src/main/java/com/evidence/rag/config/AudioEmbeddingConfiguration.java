package com.evidence.rag.config;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.AudioVectorIndexingService;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Independent local opt-in; constructing the complete graph sends no model or vector requests. */
@Configuration(proxyBeanMethods = false)
@Conditional(LegacyTextCondition.class)
@ConditionalOnProperty(prefix = "rag.audio-embedding", name = "enabled", havingValue = "true")
public class AudioEmbeddingConfiguration {
  @Bean
  AudioEmbeddingSettings audioEmbeddingSettings(
      Environment environment,
      RagProperties properties,
      TextAdapterSettings text,
      AudioDecoder decoder,
      ObjectProvider<ImageEmbeddingSettings> images) {
    try {
      requireLocal(environment);
      String prefix = "rag.audio-embedding.";
      var models =
          new GeminiAudioEmbeddingModels.Configuration(
              new OpenAiCompatibleModels.Endpoint(
                  URI.create(environment.getRequiredProperty(prefix + "base-url")),
                  environment.getRequiredProperty(prefix + "model"),
                  environment.getRequiredProperty(prefix + "api-key")),
              environment.getRequiredProperty(prefix + "revision"),
              environment.getRequiredProperty(prefix + "dimensions", Integer.class),
              environment.getRequiredProperty(prefix + "decoder-revision"),
              Duration.ofMillis(
                  environment.getProperty(prefix + "deadline-ms", Long.class, 30000L)),
              environment.getProperty(prefix + "max-response-bytes", Integer.class, 4194304),
              environment.getProperty(prefix + "allow-loopback-http", Boolean.class, false));
      String collection = environment.getRequiredProperty(prefix + "milvus.collection");
      var image = images.getIfAvailable();
      if (!collection.startsWith("java_audio")
          || collection.equals(text.projection().collection())
          || image != null && collection.equals(image.projection().collection())
          || !models.decoderRevision().equals(decoder.revision())) {
        throw invalid();
      }
      try (var client = new GeminiAudioEmbeddingModels(models)) {
        var projection =
            new MilvusRestProjection.Settings(
                URI.create(environment.getRequiredProperty(prefix + "milvus.endpoint")),
                environment.getProperty(prefix + "milvus.token", ""),
                environment.getProperty(prefix + "milvus.database", "default"),
                collection,
                properties.workspaceId(),
                client.revision(),
                models.dimensions(),
                Duration.ofMillis(
                    environment.getProperty(prefix + "milvus.deadline-ms", Long.class, 30000L)),
                environment.getProperty(
                    prefix + "milvus.max-response-bytes", Integer.class, 4194304),
                environment.getProperty(
                    prefix + "milvus.allow-loopback-http", Boolean.class, false));
        var target =
            new IndexTarget(
                client.revision(), projection.identity(), client.revision(), models.dimensions());
        return new AudioEmbeddingSettings(
            models,
            projection,
            target,
            Duration.ofMillis(
                environment.getProperty(prefix + "processing-timeout-ms", Long.class, 120000L)),
            environment.getProperty(prefix + "max-concurrent", Integer.class, 2));
      }
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean(destroyMethod = "close")
  GeminiAudioEmbeddingModels audioEmbeddingModels(AudioEmbeddingSettings settings) {
    return new GeminiAudioEmbeddingModels(settings.models());
  }

  @Bean(destroyMethod = "close")
  MilvusRestProjection audioVectorProjection(AudioEmbeddingSettings settings) {
    return new MilvusRestProjection(settings.projection());
  }

  @Bean
  IndexTarget audioVectorTarget(AudioEmbeddingSettings settings) {
    return settings.target();
  }

  @Bean
  AudioVectorIndexingService audioVectorIndexingService(
      SqliteAuthorityStore store,
      AudioVectorRepository vectors,
      EvidenceRepository evidence,
      ManagementRepository management,
      IngestionRepository ingestion,
      DocumentPermissionPolicy permissions,
      @Qualifier("indexingTarget") IndexTarget textTarget,
      AudioEmbeddingSettings settings,
      AudioDecoder decoder) {
    return new AudioVectorIndexingService(
        store,
        vectors,
        evidence,
        management,
        ingestion,
        permissions,
        textTarget,
        settings.target(),
        settings.models(),
        settings.projection(),
        decoder,
        settings.processingBudget(),
        settings.maxConcurrent());
  }

  private static void requireLocal(Environment environment) {
    String bind = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(bind) || "::1".equals(bind))
        || !("development".equals(mode) || "test".equals(mode))) {
      throw invalid();
    }
    for (String dependency :
        new String[] {"audio", "ingestion", "indexing", "answers", "query-attachments"}) {
      if (!environment.getProperty("rag." + dependency + ".enabled", Boolean.class, false)) {
        throw invalid();
      }
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid local audio embedding configuration");
  }
}
