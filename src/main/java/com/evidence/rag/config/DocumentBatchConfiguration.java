package com.evidence.rag.config;

import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.DocumentBatchService;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.ingestion", name = "enabled", havingValue = "true")
public class DocumentBatchConfiguration {
  @Bean
  DocumentBatchService documentBatchService(
      SqliteAuthorityStore store,
      IngestionService ingestion,
      IndexingService indexing,
      BatchProcessingSettings settings) {
    return new DocumentBatchService(
        store, ingestion, indexing, settings.queueCapacity(), settings.uploadConcurrency());
  }
}
