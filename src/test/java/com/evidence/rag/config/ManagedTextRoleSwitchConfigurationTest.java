package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.dto.SaveModelConfigurationCommand;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.VideoAnswerProposalService;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/** Genuine local configuration identities and real queued index JVM; no remote accounts. */
class ManagedTextRoleSwitchConfigurationTest {
  private static final Actor ADMIN = new Actor("org-main", "owner");
  @TempDir Path directory;

  @Test
  void oldVersionOneSavedActiveRestoresOnlyItsActualProfileAndUpgradesOnTheNextRoleActivation()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.repository.save(0, roles("fixture-model", "fixture-model"));
      fixture.repository.activate(1);
      var originalTarget =
          ManagedTextSettings.indexTarget(
              fixture.settings.adapters(
                  roles("fixture-model", "fixture-model"), ADMIN.workspaceId()));
      fixture.queue(originalTarget);
      try (var runtime = fixture.start()) {
        assertEquals(originalTarget, runtime.currentTarget());
        assertEquals(1L, runtime.currentAnchor().originatingVersion());
        assertNull(
            fixture.repository.read().indexAnchor(),
            "Reading v1 does not silently rewrite private settings");
        assertTrue(fixture.models.requests.isEmpty());
        assertTrue(fixture.projection.requests.isEmpty());
        var service =
            fixture.configuration.modelConfigurationService(
                fixture.authority.store(),
                fixture.repository,
                fixture.configuration.modelConfigurationPermissionPolicy(
                    fixture.properties, fixture.settings),
                runtime,
                fixture.configuration.textModelConnectionProbe(fixture.settings));
        service.save(ADMIN, command(1, "new-rerank", "new-generation"));
        service.activate(ADMIN, 2);
        assertEquals(originalTarget, runtime.currentTarget());
        assertEquals(1L, fixture.repository.read().indexAnchor().originatingVersion());
        assertNotEquals(originalTarget.modelRevision(), runtime.currentModelsRevision());
        assertTrue(fixture.models.requests.isEmpty());
        assertTrue(fixture.projection.requests.isEmpty());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1", "v2"})
  void startupChecksTheCompleteExistingTargetSetBeforeInstallingAnySnapshot(String format)
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      var roles = roles("fixture-model", "fixture-model");
      var adapters = fixture.settings.adapters(roles, ADMIN.workspaceId());
      var anchor = fixture.settings.anchor(1, roles, adapters);
      fixture.repository.save(0, roles);
      if (format.equals("v1")) {
        fixture.repository.activate(1);
      } else {
        fixture.repository.activate(1, anchor);
      }
      fixture.queue(anchor.target());
      fixture.queue(
          new IndexTarget(
              anchor.target().embeddingIdentity(),
              anchor.target().projectionIdentity(),
              "unproven-old-complete-revision",
              2));
      var before = fixture.repository.read();
      assertEquals(
          "model_rebuild_required",
          assertThrows(ApplicationException.class, fixture::start).code());
      assertEquals(before, fixture.repository.read());
      assertTrue(fixture.authority.store().operationGate().isIdle());
      assertTrue(fixture.models.requests.isEmpty());
      assertTrue(fixture.projection.requests.isEmpty());
    }
  }

  @Test
  void versionTwoRestartUsesTheOriginalIndexMetadataAndTheCurrentActualAnswerRolesWithoutNetwork()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      var originalRoles = roles("fixture-model", "fixture-model");
      var anchor =
          fixture.settings.anchor(
              1, originalRoles, fixture.settings.adapters(originalRoles, ADMIN.workspaceId()));
      fixture.repository.save(0, originalRoles);
      fixture.repository.activate(1, anchor);
      fixture.queue(anchor.target());
      var changed = roles("new-rerank", "new-generation");
      fixture.repository.save(1, changed);
      fixture.repository.activate(2, anchor);
      try (var runtime = fixture.start()) {
        assertEquals(anchor, runtime.currentAnchor());
        assertEquals(anchor.target(), runtime.currentTarget());
        try (var actual =
            new OpenAiCompatibleModels(
                fixture.settings.adapters(changed, ADMIN.workspaceId()).models())) {
          assertEquals(actual.revision(), runtime.currentModelsRevision());
          assertNotEquals(anchor.target().modelRevision(), actual.revision());
        }
        assertTrue(fixture.models.requests.isEmpty());
        assertTrue(fixture.projection.requests.isEmpty());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"provider", "projection", "original-role"})
  void restoredAnchorMustRecomputeAgainstTheTrustedServerAndItsExactOriginalFullProfile(
      String changed) throws Exception {
    try (var fixture = new Fixture(directory)) {
      var original = roles("fixture-model", "fixture-model");
      var anchor =
          fixture.settings.anchor(
              1, original, fixture.settings.adapters(original, ADMIN.workspaceId()));
      fixture.repository.save(0, original);
      fixture.repository.activate(1, anchor);
      if (changed.equals("provider")) {
        fixture.environment.withProperty(
            "rag.model-configuration.provider-base-url", "https://different-synthetic.invalid/v1");
      } else if (changed.equals("projection")) {
        fixture.environment.withProperty("RAG_MILVUS_COLLECTION", "java_different_fixture");
      } else {
        anchor =
            new TextIndexAnchor(
                anchor.originatingVersion(),
                anchor.providerBaseUrl(),
                anchor.embeddingModel(),
                anchor.embeddingRevision(),
                anchor.dimensions(),
                "incorrect-original-rerank",
                anchor.generationModel(),
                anchor.target());
      }
      var checked = anchor;
      var settings = new ManagedTextSettings(fixture.environment);
      assertEquals(
          "model_rebuild_required",
          assertThrows(
                  ApplicationException.class,
                  () -> settings.indexAdapters(original, checked, ADMIN.workspaceId()))
              .code());
      assertTrue(fixture.models.requests.isEmpty());
      assertTrue(fixture.projection.requests.isEmpty());
    }
  }

  @Test
  void queuedOldTargetRunsTheRealIndexWorkerAfterRoleSwitchWithoutCallingOldAnswerRoles()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.repository.save(0, roles("fixture-model", "fixture-model"));
      fixture.repository.activate(1);
      try (var runtime = fixture.start()) {
        var target = runtime.currentTarget();
        String task = fixture.queue(target);
        var service =
            fixture.configuration.modelConfigurationService(
                fixture.authority.store(),
                fixture.repository,
                fixture.configuration.modelConfigurationPermissionPolicy(
                    fixture.properties, fixture.settings),
                runtime,
                fixture.configuration.textModelConnectionProbe(fixture.settings));
        service.save(ADMIN, command(1, "new-rerank", "new-generation"));
        service.activate(ADMIN, 2);
        assertTrue(fixture.models.requests.isEmpty());
        assertTrue(fixture.projection.requests.isEmpty());
        try (var operation = fixture.authority.store().operationGate().enter()) {
          var snapshot = runtime.capture();
          var claim = snapshot.indexing().claim().orElseThrow();
          assertEquals(target, claim.target());
          snapshot.indexing().process(claim);
        }
        assertEquals("indexed", fixture.authority.indexing().indexingStatus(ADMIN, task).state());
        assertFalse(fixture.projection.committedUpserts.isEmpty());
        assertEquals(1, fixture.models.requests.size());
        assertEquals("/embeddings", fixture.models.requests.getFirst().path());
        assertEquals(
            "fixture-model", fixture.models.requests.getFirst().body().path("model").asString());
        assertTrue(fixture.authority.store().operationGate().isIdle());
      }
    }
  }

  private static TextModelConfiguration roles(String rerank, String generation) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            "fixture-model", "synthetic-embedding-key", 2, "fixture-v1"),
        new TextModelConfiguration.Role(rerank, "synthetic-rerank-key"),
        new TextModelConfiguration.Role(generation, "synthetic-generation-key"));
  }

  private static SaveModelConfigurationCommand command(
      long base, String rerank, String generation) {
    return new SaveModelConfigurationCommand(
        base,
        new SaveModelConfigurationCommand.EmbeddingInput("fixture-model", 2, "fixture-v1", null),
        new SaveModelConfigurationCommand.RoleInput(rerank, null),
        new SaveModelConfigurationCommand.RoleInput(generation, null));
  }

  private static final class Fixture implements AutoCloseable {
    final AnswerProtocolServer models;
    final IndexingTestServer projection;
    final AuthorityTestContext authority;
    final ModelConfigurationRepository repository;
    final MockEnvironment environment;
    final ManagedTextSettings settings;
    final RagProperties properties;
    final ModelConfigurationConfiguration configuration = new ModelConfigurationConfiguration();
    final EvidenceService evidence;

    Fixture(Path directory) throws Exception {
      models = new AnswerProtocolServer();
      projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint());
      authority = new AuthorityTestContext(directory.resolve("authority"));
      repository = new ModelConfigurationRepository(directory.resolve("private/text-models.json"));
      environment =
          new MockEnvironment()
              .withProperty("server.address", "127.0.0.1")
              .withProperty("rag.environment", "test")
              .withProperty("rag.model-configuration.enabled", "true")
              .withProperty("rag.model-configuration.administrators", "owner")
              .withProperty(
                  "rag.model-configuration.provider-base-url", models.endpoint().toASCIIString())
              .withProperty("rag.model-configuration.allow-loopback-http", "true")
              .withProperty("rag.model-configuration.deadline-ms", "5000")
              .withProperty("RAG_MILVUS_ENDPOINT", projection.endpoint().toASCIIString())
              .withProperty("RAG_MILVUS_TOKEN", "synthetic-projection-credential")
              .withProperty("RAG_MILVUS_COLLECTION", "java_index_process_fixture");
      settings = new ManagedTextSettings(environment);
      properties =
          new RagProperties(
              "test",
              "org-main",
              "development_headers",
              null,
              null,
              null,
              directory.resolve("authority"));
      evidence =
          new EvidenceService(
              authority.store(),
              new EvidenceRepository(authority.store()),
              new ManagementRepository(authority.store()),
              new DocumentPermissionPolicy());
    }

    ManagedTextRuntime start() {
      var providers = new DefaultListableBeanFactory();
      return configuration.managedTextRuntime(
          authority.store(),
          authority.indexing(),
          evidence,
          properties,
          new ManagedTextSettings(environment),
          new AnswersSettings(true, 10000, 2),
          new IndexingSettings(true, 10000),
          repository,
          providers.getBeanProvider(VideoAnswerProposalService.class),
          providers.getBeanProvider(QueryAttachmentService.class));
    }

    String queue(IndexTarget target) {
      byte[] source = "星港项目的识别码为A-42。".getBytes(StandardCharsets.UTF_8);
      authority.uploadDocument(ADMIN, "plan.txt", "text/plain", source);
      var claim = authority.claimIngestion(ADMIN.workspaceId()).orElseThrow();
      assertTrue(
          authority.completeIngestion(
              claim, new TextParser().parse("plan.txt", "text/plain", source)));
      return authority.indexing().createIndexing(ADMIN, claim.documentId(), target).taskId();
    }

    @Override
    public void close() {
      repository.close();
      authority.close();
      projection.close();
      models.close();
    }
  }
}
