package com.evidence.rag.config;

import com.evidence.rag.repository.RetrievalSettingsRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.RetrievalSettingsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public class RetrievalSettingsConfiguration {
  @Bean
  RetrievalSettingsRepository retrievalSettingsRepository(
      SqliteAuthorityStore store, RagProperties properties) {
    return new RetrievalSettingsRepository(
        store.libraryPath().getParent().resolve("retrieval-settings.json"),
        properties.workspaceId());
  }

  @Bean
  RetrievalSettingsService retrievalSettingsService(
      RetrievalSettingsRepository repository, RagProperties properties) {
    return new RetrievalSettingsService(repository, properties.workspaceId());
  }
}
