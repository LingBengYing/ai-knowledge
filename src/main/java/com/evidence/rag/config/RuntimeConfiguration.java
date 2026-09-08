package com.evidence.rag.config;

import com.evidence.rag.service.RuntimeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  @Bean
  RuntimeService runtimeService(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal) {
    return new RuntimeService(
        p.authMode(),
        p.workspaceId(),
        ingestion.enabled(),
        indexing.enabled(),
        answers.enabled(),
        removal.enabled());
  }
}
