package com.evidence.rag.config;

import com.evidence.rag.service.RuntimeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  @Bean
  RuntimeService runtimeService(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment) {
    return new RuntimeService(
        p.authMode(),
        p.workspaceId(),
        ingestion.enabled(),
        indexing.enabled(),
        answers.enabled(),
        removal.enabled(),
        ImageOcrConfiguration.options(environment) != null,
        environment.getProperty("rag.visual.enabled", Boolean.class, false),
        environment.getProperty("rag.audio.enabled", Boolean.class, false),
        environment.getProperty("rag.video.enabled", Boolean.class, false),
        environment.getProperty("rag.synopsis.enabled", Boolean.class, false),
        environment.getProperty("rag.query-attachments.enabled", Boolean.class, false));
  }
}
