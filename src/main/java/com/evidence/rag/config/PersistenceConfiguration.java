package com.evidence.rag.config;

import com.evidence.rag.bootstrap.DemoFixtures;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagementService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Single authority owner and explicit startup recovery, before dependent background jobs exist. */
@Configuration
public class PersistenceConfiguration {
  /** Standalone CLI composition keeps persistence out of the synthetic bootstrap use case. */
  public static void seedDemo(Path directory) {
    if (Files.exists(directory.resolve("java-library.db"))) {
      throw new IllegalStateException(
          "Demo import requires a new empty database; existing data is never overwritten");
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var management = new ManagementRepository(store);
      var ingestion = new IngestionRepository(store);
      var indexing = new IndexingRepository(store);
      DemoFixtures.seed(
          new ManagementService(
              store, management, ingestion, indexing, new DocumentPermissionPolicy()));
    }
  }

  @Bean(destroyMethod = "close")
  SqliteAuthorityStore authorityStore(RagProperties properties, Environment environment) {
    RuntimeGuard.check(properties, environment.getProperty("server.address"));
    return new SqliteAuthorityStore(properties.dataDirectory());
  }

  @Bean
  ManagementRepository managementRepository(SqliteAuthorityStore store) {
    return new ManagementRepository(store);
  }

  @Bean
  IngestionRepository ingestionRepository(SqliteAuthorityStore store) {
    return new IngestionRepository(store);
  }

  @Bean
  IndexingRepository indexingRepository(SqliteAuthorityStore store) {
    return new IndexingRepository(store);
  }

  @Bean
  DocumentPermissionPolicy documentPermissionPolicy() {
    return new DocumentPermissionPolicy();
  }

  @Bean
  ManagementService managementService(
      SqliteAuthorityStore store,
      ManagementRepository management,
      IngestionRepository ingestion,
      IndexingRepository indexing,
      DocumentPermissionPolicy permissions) {
    return new ManagementService(store, management, ingestion, indexing, permissions);
  }

  @Bean
  IngestionService ingestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    var service = new IngestionService(store, ingestion, management, permissions);
    service.recoverIngestions();
    return service;
  }

  @Bean
  IndexingService indexingService(
      SqliteAuthorityStore store,
      IndexingRepository indexing,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    var service = new IndexingService(store, indexing, management, permissions);
    service.recoverIndexings();
    return service;
  }
}
