package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.ImageVectorIndexingService;
import com.evidence.rag.service.ManagedTextRuntime;
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
@Conditional(MediaModulesCondition.class)
@ConditionalOnProperty(prefix = "rag.image-embedding", name = "enabled", havingValue = "true")
public class ImageEmbeddingConfiguration {
  @Bean
  ImageEmbeddingSettings imageEmbeddingSettings(
      Environment environment, RagProperties properties) {
    try {
      requireLocal(environment);
      String prefix = "rag.image-embedding.";
      var models =
          new SiliconFlowImageEmbeddingModels.Configuration(
              new OpenAiCompatibleModels.Endpoint(
                  URI.create(environment.getRequiredProperty(prefix + "base-url")),
                  environment.getRequiredProperty(prefix + "model"),
                  environment.getRequiredProperty(prefix + "api-key")),
              environment.getRequiredProperty(prefix + "revision"),
              environment.getRequiredProperty(prefix + "dimensions", Integer.class),
              Duration.ofMillis(
                  environment.getProperty(prefix + "deadline-ms", Long.class, 30000L)),
              environment.getProperty(prefix + "max-response-bytes", Integer.class, 4194304),
              environment.getProperty(prefix + "allow-loopback-http", Boolean.class, false));
      String collection = environment.getRequiredProperty(prefix + "milvus.collection");
      String textCollection = environment.getProperty("RAG_MILVUS_COLLECTION",
          environment.getProperty("rag.milvus.collection"));
      if (!collection.startsWith("java_image")
          || textCollection != null && collection.equals(textCollection)) {
        throw invalid();
      }
      try (var client = new SiliconFlowImageEmbeddingModels(models)) {
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
        return new ImageEmbeddingSettings(
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

  // Preserve the existing explicit legacy construction without making it a Spring dependency.
  ImageEmbeddingSettings imageEmbeddingSettings(
      Environment environment, RagProperties properties, TextAdapterSettings text) {
    if (text == null) {
      throw invalid();
    }
    var settings = imageEmbeddingSettings(environment, properties);
    if (settings.projection().collection().equals(text.projection().collection())) {
      throw invalid();
    }
    return settings;
  }

  @Bean(destroyMethod = "close")
  SiliconFlowImageEmbeddingModels imageEmbeddingModels(ImageEmbeddingSettings settings) {
    return new SiliconFlowImageEmbeddingModels(settings.models());
  }

  @Bean(destroyMethod = "close")
  MilvusRestProjection imageVectorProjection(ImageEmbeddingSettings settings) {
    return new MilvusRestProjection(settings.projection());
  }

  @Bean
  IndexTarget imageVectorTarget(ImageEmbeddingSettings settings) {
    return settings.target();
  }

  @Bean
  ImageVectorIndexingService imageVectorIndexingService(
      SqliteAuthorityStore store,
      ImageVectorRepository vectors,
      EvidenceRepository evidence,
      ManagementRepository management,
      IngestionRepository ingestion,
      DocumentPermissionPolicy permissions,
      @Qualifier("indexingTarget") ObjectProvider<IndexTarget> textTarget,
      ImageEmbeddingSettings settings, ObjectProvider<ManagedTextRuntime> managed) {
    return ImageVectorIndexingService.managed(
        store, vectors, evidence, management, ingestion, permissions,
        () -> {
          var runtime = managed.getIfAvailable();
          return runtime == null ? textTarget.getIfAvailable() : runtime.currentTarget();
        },
        settings.target(), settings.models(), settings.projection(),
        settings.processingBudget(), settings.maxConcurrent());
  }

  private static void requireLocal(Environment environment) {
    String bind = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(bind) || "::1".equals(bind))
        || !("development".equals(mode) || "test".equals(mode))) {
      throw invalid();
    }
    for (String dependency : new String[] {"indexing", "visual", "answers", "query-attachments"}) {
      if (!environment.getProperty("rag." + dependency + ".enabled", Boolean.class, false)) {
        throw invalid();
      }
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid local image embedding configuration");
  }
}
