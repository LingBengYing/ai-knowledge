package com.evidence.rag.config;

import com.evidence.rag.bootstrap.DemoFixtures;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.service.ReindexVectorVerifier;
import com.evidence.rag.service.SynopsisLibraryService;
import com.evidence.rag.service.VideoCompilationService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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

  /** Recovery needs only authority storage, even when the paid synopsis feature is disabled. */
  @Bean
  SynopsisRepository synopsisRepository(SqliteAuthorityStore store, RagProperties properties) {
    var repository = new SynopsisRepository(store);
    SynopsisLibraryService.recoverProcessing(store, repository, properties.workspaceId());
    return repository;
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
  ImageVectorRepository imageVectorRepository(SqliteAuthorityStore store) {
    return new ImageVectorRepository(store);
  }

  @Bean
  AudioVectorRepository audioVectorRepository(SqliteAuthorityStore store) {
    return new AudioVectorRepository(store);
  }

  @Bean
  VideoAvRepository videoAvRepository(SqliteAuthorityStore store) {
    return new VideoAvRepository(store);
  }

  @Bean
  SoundRepository soundRepository(SqliteAuthorityStore store) {
    return new SoundRepository(store);
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
      DocumentPermissionPolicy permissions,
      IndexingSettings indexingSettings,
      IndexingService authority,
      ObjectProvider<ManagedTextRuntime> managed,
      @Qualifier("indexingTarget") ObjectProvider<IndexTarget> legacyTarget,
      ObjectProvider<IndexingTaskProcessor> processors) {
    return new ManagementService(
        store,
        management,
        ingestion,
        indexing,
        permissions,
        indexingSettings.enabled(),
        (actor, documentId) -> {
          var runtime = managed.getIfAvailable();
          var current = runtime == null ? legacyTarget.getIfAvailable() : runtime.currentTarget();
          return current != null
              && authority.canReindexWithVectorsInTransaction(actor, documentId, current);
        },
        () -> {
          var runtime = managed.getIfAvailable();
          return runtime == null ? processors.getIfAvailable() : runtime.capture().indexing();
        });
  }

  @Bean
  IngestionService ingestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      Environment environment,
      ObjectProvider<VisualIngestionOptions> visual,
      ObjectProvider<AudioCompilationService> audio,
      ObjectProvider<VideoCompilationService> video) {
    var compiler = audio.getIfAvailable();
    var videoCompiler = video.getIfAvailable();
    var service =
        new IngestionService(
            store,
            ingestion,
            management,
            permissions,
            ImageOcrConfiguration.options(environment),
            visual.getIfAvailable(),
            compiler == null ? null : compiler.revision(),
            videoCompiler == null ? null : videoCompiler.revision(),
            videoCompiler != null && videoCompiler.ocrEnabled(),
            videoCompiler != null && videoCompiler.subtitlesEnabled(),
            PdfOcrConfiguration.options(environment));
    service.recoverIngestions();
    return service;
  }

  @Bean
  IndexingService indexingService(
      SqliteAuthorityStore store,
      IndexingRepository indexing,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ObjectProvider<ReindexVectorVerifier> receiptVerifiers) {
    var service =
        new IndexingService(
            store,
            indexing,
            management,
            permissions,
            (route, target) -> receiptVerifiers.getObject().supports(route, target));
    service.recoverIndexings();
    return service;
  }
}
