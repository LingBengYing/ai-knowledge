package com.evidence.rag.config;

import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Local-only lifecycle composition with no provider configuration or remote clients. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentRemovalSettings.class)
public class DocumentRemovalConfiguration {
  @Bean
  @ConditionalOnProperty(prefix = "rag.document-removal", name = "enabled", havingValue = "true")
  DocumentLifecycleRepository documentLifecycleRepository(SqliteAuthorityStore store) {
    return new DocumentLifecycleRepository(store);
  }

  @Bean
  @ConditionalOnProperty(prefix = "rag.document-removal", name = "enabled", havingValue = "true")
  DocumentLifecycleService documentLifecycleService(
      SqliteAuthorityStore store,
      DocumentLifecycleRepository lifecycle,
      ManagementRepository management,
      IngestionRepository ingestion,
      IndexingRepository indexing,
      DocumentPermissionPolicy permissions,
      RagProperties properties,
      Environment environment) {
    String address = environment.getProperty("server.address");
    if (!("development".equals(properties.environment()) || "test".equals(properties.environment()))
        || !("127.0.0.1".equals(address) || "::1".equals(address))) {
      throw new IllegalArgumentException(
          "Document removal requires a local development or test runtime");
    }
    return new DocumentLifecycleService(
        store, lifecycle, management, ingestion, indexing, permissions);
  }
}
