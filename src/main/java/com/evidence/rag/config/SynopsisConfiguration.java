package com.evidence.rag.config;

import com.evidence.rag.client.model.HierarchicalSynopsisModels;
import com.evidence.rag.client.model.OpenAiCompatibleHierarchicalSynopsisModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleSynopsisModels;
import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.job.SynopsisJob;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.HierarchicalSynopsisService;
import com.evidence.rag.service.SynopsisLibraryService;
import com.evidence.rag.service.SynopsisService;
import com.evidence.rag.service.SynopsisTaskProcessor;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit independent local synopsis composition; no answer or vector dependency. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.synopsis", name = "enabled", havingValue = "true")
public class SynopsisConfiguration {
  @Bean(destroyMethod = "close")
  OpenAiCompatibleSynopsisModels synopsisModels(Environment environment) {
    return new OpenAiCompatibleSynopsisModels(modelConfiguration(environment));
  }

  @Bean(destroyMethod = "close")
  OpenAiCompatibleHierarchicalSynopsisModels hierarchicalSynopsisModels(Environment environment) {
    return new OpenAiCompatibleHierarchicalSynopsisModels(modelConfiguration(environment));
  }

  private static OpenAiCompatibleSynopsisModels.Configuration modelConfiguration(
      Environment environment) {
    try {
      String address = environment.getProperty("server.address");
      String mode = environment.getProperty("rag.environment", "development");
      if (!("127.0.0.1".equals(address) || "::1".equals(address))
          || !("development".equals(mode) || "test".equals(mode))) {
        throw new IllegalArgumentException();
      }
      return new OpenAiCompatibleSynopsisModels.Configuration(
          new OpenAiCompatibleModels.Endpoint(
              URI.create(environment.getRequiredProperty("rag.synopsis.base-url")),
              environment.getRequiredProperty("rag.synopsis.model"),
              environment.getRequiredProperty("rag.synopsis.api-key")),
          Duration.ofMillis(
              environment.getProperty("rag.synopsis.deadline-ms", Long.class, 30000L)),
          environment.getProperty("rag.synopsis.max-response-bytes", Integer.class, 262144),
          environment.getProperty("rag.synopsis.allow-loopback-http", Boolean.class, false));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid local synopsis configuration");
    }
  }

  @Bean
  SynopsisService synopsisService(SynopsisModels models, Environment environment) {
    try {
      return new SynopsisService(
          models,
          Duration.ofMillis(
              environment.getProperty("rag.synopsis.budget-ms", Long.class, 600000L)));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid local synopsis configuration");
    }
  }

  @Bean
  HierarchicalSynopsisService hierarchicalSynopsisService(
      HierarchicalSynopsisModels models, Environment environment) {
    try {
      return new HierarchicalSynopsisService(
          models,
          Duration.ofMillis(
              environment.getProperty("rag.synopsis.budget-ms", Long.class, 600000L)));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid local synopsis configuration");
    }
  }

  @Bean
  SynopsisMaterialRepository synopsisMaterialRepository(SqliteAuthorityStore store) {
    return new SynopsisMaterialRepository(store);
  }

  @Bean
  SynopsisLibraryService synopsisLibraryService(
      SqliteAuthorityStore store,
      SynopsisRepository repository,
      SynopsisMaterialRepository materials,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      SynopsisModels models,
      HierarchicalSynopsisModels hierarchyModels,
      RagProperties properties) {
    return new SynopsisLibraryService(
        store,
        repository,
        materials,
        management,
        permissions,
        models::revision,
        hierarchyModels::revision);
  }

  @Bean
  SynopsisTaskProcessor synopsisTaskProcessor(
      SynopsisLibraryService library,
      SynopsisService generator,
      HierarchicalSynopsisService hierarchyGenerator,
      RagProperties properties) {
    return new SynopsisTaskProcessor(
        library, generator, hierarchyGenerator, properties.workspaceId());
  }

  @Bean(destroyMethod = "close")
  SynopsisJob synopsisJob(SynopsisTaskProcessor processor) {
    return new SynopsisJob(processor);
  }
}
