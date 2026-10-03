package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IndexingTaskProcessor;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/** Explicit configuration only. Remote writes start with an authorized indexing task. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.indexing", name = "enabled", havingValue = "true")
public class IndexingConfiguration {
  static TextAdapterSettings loadAdapters(ConfigurableEnvironment environment, String workspace) {
    String bind = environment.getProperty("server.address");
    if (!("127.0.0.1".equals(bind) || "::1".equals(bind))) {
      throw new IllegalArgumentException(
          "Development text adapters require literal loopback binding");
    }
    var values = new LinkedHashMap<String, String>();
    var prefixes =
        List.of("RAG_EMBEDDING_", "RAG_RERANK_", "RAG_GENERATION_", "RAG_MILVUS_", "RAG_TEXT_");
    for (var source : environment.getPropertySources()) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        for (String name : enumerable.getPropertyNames()) {
          if (prefixes.stream().anyMatch(name::startsWith)) {
            values.put(name, environment.getProperty(name));
          }
        }
      }
    }
    values.put("RAG_WORKSPACE_ID", workspace);
    return TextAdapterSettings.load(values);
  }

  @Bean
  @Conditional(LegacyTextCondition.class)
  @Primary
  IndexTarget indexingTarget(TextAdapterSettings settings) {
    try (var models = new OpenAiCompatibleModels(settings.models())) {
      return new IndexTarget(
          settings.projection().embeddingIdentity(),
          settings.projection().identity(),
          models.revision(),
          settings.projection().dimension());
    }
  }

  @Bean
  @Conditional(LegacyTextCondition.class)
  IndexingTaskProcessor indexingTaskProcessor(
      IndexingService authority,
      RagProperties properties,
      TextAdapterSettings settings,
      IndexingSettings limits,
      IndexTarget target) {
    return new IndexingTaskProcessor(
        authority,
        properties.workspaceId(),
        settings.models(),
        settings.projection(),
        target,
        Duration.ofMillis(limits.timeoutMs()));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "rag.model-configuration",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  IndexingJob indexingJob(
      IndexingTaskProcessor processor, ObjectProvider<LibraryOperationGate> operations) {
    return new IndexingJob(processor, operations.getIfAvailable());
  }
}
