package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.job.DocumentCleanupJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** Deployment-shaped managed configuration; model and vector transports are never dispatched. */
class ManagedDocumentCleanupConfigurationTest {
  private static final Actor ACTOR = new Actor("org-main", "owner");
  @TempDir Path directory;

  @Test
  void initialManagedTargetIsResolvedWithoutLegacySettingsOrARebuild() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var projection = fixture.context.getBean(MilvusProjectionCleanup.class);
      var expected = fixture.settings.adapters(fixture.roles, ACTOR.workspaceId()).projection();
      assertTrue(fixture.context.getBeansOfType(TextAdapterSettings.class).isEmpty());
      assertTrue(projection.settingsFor(MilvusProjectionCleanup.qualified(expected)).isPresent());
      var foreign =
          new MilvusRestProjection.Settings(
              expected.endpoint(),
              expected.token(),
              expected.database(),
              "java_unregistered_old_collection",
              expected.workspaceId(),
              expected.embeddingIdentity(),
              expected.dimension(),
              expected.timeout(),
              expected.maxResponseBytes(),
              expected.allowLoopbackHttp());
      assertTrue(projection.settingsFor(MilvusProjectionCleanup.qualified(foreign)).isEmpty());
      var current = MilvusProjectionCleanup.qualified(expected);
      var wrongCollection =
          new QualifiedProjectionTarget(
              current.endpoint(),
              current.database(),
              "java_other_collection",
              current.workspaceId(),
              current.embeddingIdentity(),
              current.dimensions(),
              current.projectionIdentity());
      assertTrue(projection.settingsFor(wrongCollection).isEmpty());
      fixture.runtime.close();
      assertTrue(projection.settingsFor(MilvusProjectionCleanup.qualified(expected)).isEmpty());
    }
  }

  @Test
  void deploymentGraphActuallyClearsLocalPayloadAndKeepsUnrelatedOriginal() throws Exception {
    String removed;
    byte[] retained = "retained unrelated cleanup fixture".getBytes(StandardCharsets.UTF_8);
    try (var fixture = new Fixture(directory)) {
      fixture.context.getBean(DocumentCleanupJob.class).close();
      removed = fixture.parsed("new local cleanup fixture".getBytes(StandardCharsets.UTF_8));
      String other = fixture.parsed(retained);
      var service = fixture.context.getBean(DocumentCleanupService.class);
      assertEquals("pending", service.request(ACTOR, removed).cleanupStatus());
      assertTrue(service.runOnce());
      assertEquals("completed", service.status(ACTOR, removed).cleanupStatus());
      assertFalse(service.runOnce());
      assertArrayEquals(
          retained,
          fixture
              .authority
              .store()
              .transaction(
                  () -> new IngestionRepository(fixture.authority.store()).original(other)));
      assertEquals(
          0,
          fixture
              .authority
              .store()
              .transaction(
                  () -> new IngestionRepository(fixture.authority.store()).original(removed))
              .length);
    }
    try (var reopened = new SqliteAuthorityStore(directory.resolve("authority"))) {
      assertEquals(
          "completed",
          reopened.transaction(
              () ->
                  new DocumentCleanupRepository(reopened)
                      .find(ACTOR, removed)
                      .orElseThrow()
                      .cleanupStatus()));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AuthorityTestContext authority;
    final ModelConfigurationRepository repository;
    final ManagedTextSettings settings;
    final ManagedTextRuntime runtime;
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    final TextModelConfiguration roles =
        new TextModelConfiguration(
            new TextModelConfiguration.Embedding("fixture-model", "fixture-key", 2, "fixture-v1"),
            new TextModelConfiguration.Role("fixture-model", "fixture-key"),
            new TextModelConfiguration.Role("fixture-model", "fixture-key"));

    Fixture(Path directory) {
      authority = new AuthorityTestContext(directory.resolve("authority"));
      repository = new ModelConfigurationRepository(directory.resolve("private/text-models.json"));
      var environment =
          new MockEnvironment()
              .withProperty("server.address", "127.0.0.1")
              .withProperty("rag.environment", "development")
              .withProperty("rag.document-cleanup.enabled", "true")
              .withProperty("rag.model-configuration.enabled", "true")
              .withProperty("rag.model-configuration.provider-base-url", "http://127.0.0.1:1/v1")
              .withProperty("rag.model-configuration.allow-loopback-http", "true")
              .withProperty("RAG_MILVUS_ENDPOINT", "http://127.0.0.1:1")
              .withProperty("RAG_MILVUS_TOKEN", "synthetic-projection-credential")
              .withProperty("RAG_MILVUS_COLLECTION", "java_initial_managed_collection");
      settings = new ManagedTextSettings(environment);
      var properties =
          new RagProperties(
              "development",
              "org-main",
              "development_headers",
              null,
              null,
              null,
              directory.resolve("authority"));
      var evidence =
          new EvidenceService(
              authority.store(),
              new EvidenceRepository(authority.store()),
              new ManagementRepository(authority.store()),
              new DocumentPermissionPolicy());
      var providers = new DefaultListableBeanFactory();
      providers.registerSingleton("evidenceService", evidence);
      providers.registerSingleton("answersSettings", new AnswersSettings(true, 10000, 2));
      repository.save(0, roles);
      repository.activate(1);
      runtime =
          new ModelConfigurationConfiguration()
              .managedTextRuntime(
                  authority.store(),
                  authority.indexing(),
                  evidence,
                  properties,
                  settings,
                  new AnswersSettings(true, 10000, 2),
                  new IndexingSettings(true, 10000),
                  repository,
                  providers.createBean(ManagedMediaTextFactory.class));
      context.setEnvironment(environment);
      context.register(DocumentCleanupConfiguration.class);
      context.registerBean(RagProperties.class, () -> properties);
      context.registerBean(SqliteAuthorityStore.class, authority::store);
      context.registerBean(ManagedTextSettings.class, () -> settings);
      context.registerBean(ManagedTextRuntime.class, () -> runtime);
      context.registerBean(DocumentRemovalSettings.class, () -> new DocumentRemovalSettings(true));
      context.registerBean(
          DocumentLifecycleRepository.class,
          () -> new DocumentLifecycleRepository(authority.store()));
      context.registerBean(
          ManagementRepository.class, () -> new ManagementRepository(authority.store()));
      context.registerBean(DocumentPermissionPolicy.class, DocumentPermissionPolicy::new);
      context.refresh();
      assertTrue(context.getBeansOfType(ProjectionCleanup.class).size() == 1);
    }

    String parsed(byte[] source) {
      var task = authority.ingestion().uploadDocument(ACTOR, "fixture.txt", "text/plain", source);
      var claim = authority.ingestion().claimIngestion(ACTOR.workspaceId()).orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  claim, new TextParser().parse("fixture.txt", "text/plain", source)));
      return task.documentId();
    }

    @Override
    public void close() {
      context.close();
      runtime.close();
      repository.close();
      authority.close();
    }
  }
}
