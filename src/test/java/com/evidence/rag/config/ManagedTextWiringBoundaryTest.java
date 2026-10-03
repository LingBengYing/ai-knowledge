package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.RuntimeController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.domain.TextModelRole;
import com.evidence.rag.model.dto.RuntimeCapabilities;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.support.AnswerProtocolServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Trusted operator settings and actual graph capability boundaries; no environment secrets. */
class ManagedTextWiringBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "localhost,test",
    "0.0.0.0,development",
    "[::1],test",
    "127.0.0.1,production",
    "127.0.0.1,staging"
  })
  void managedSetupDoesNotBypassExistingLiteralLoopbackAndLocalMode(String bind, String mode) {
    var environment =
        local().withProperty("server.address", bind).withProperty("rag.environment", mode);
    var error =
        assertThrows(IllegalArgumentException.class, () -> new ManagedTextSettings(environment));
    assertEquals("Invalid managed text server configuration", error.getMessage());
    assertNull(error.getCause());
  }

  @ParameterizedTest
  @CsvSource({"127.0.0.1,development", "127.0.0.1,test", "::1,development", "::1,test"})
  void localTrustedBindingsConstructWithoutModelRolesOrProjection(String bind, String mode) {
    var environment =
        local().withProperty("server.address", bind).withProperty("rag.environment", mode);
    var settings = new ManagedTextSettings(environment);
    assertEquals(Set.of("owner"), settings.administrators());
    assertNull(settings.projection());
    assertNull(ManagedTextSettings.bootstrap(environment));
    assertFalse(ManagedTextSettings.legacyAvailable(environment));
    assertFalse(settings.toString().contains("api.siliconflow"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "owner,", "bad principal", "owner\nsecond"})
  void organizationAdministratorsAreExplicitAndCannotDefaultToDocumentOwner(String administrators) {
    var environment =
        local().withProperty("rag.model-configuration.administrators", administrators);
    assertThrows(IllegalArgumentException.class, () -> new ManagedTextSettings(environment));
    if (administrators.isEmpty()) {
      var omitted =
          new MockEnvironment()
              .withProperty("server.address", "127.0.0.1")
              .withProperty("rag.environment", "test")
              .withProperty("rag.model-configuration.enabled", "true");
      assertThrows(IllegalArgumentException.class, () -> new ManagedTextSettings(omitted));
    }
  }

  @Test
  void unknownServerSettingFailsWithoutEchoingItsNameOrValue() {
    var environment =
        local()
            .withProperty(
                "RAG_MODEL_CONFIGURATION_UNRECOGNIZED_SECRET", "synthetic-never-echo-this-value");
    var error =
        assertThrows(IllegalArgumentException.class, () -> new ManagedTextSettings(environment));
    assertEquals("Invalid managed text server configuration", error.getMessage());
    assertFalse(error.getMessage().contains("SECRET"));
    assertFalse(error.getMessage().contains("synthetic-never-echo"));
    assertNull(error.getCause());
  }

  @Test
  void oneExplicitEmbeddingProbeNeedsNoMilvusOrRuntimeBundle() throws Exception {
    try (var server = new AnswerProtocolServer()) {
      var environment =
          local()
              .withProperty(
                  "rag.model-configuration.provider-base-url", server.endpoint().toASCIIString())
              .withProperty("rag.model-configuration.allow-loopback-http", "true");
      var settings = new ManagedTextSettings(environment);
      assertNull(settings.projection());
      var probe = new ModelConfigurationConfiguration().textModelConnectionProbe(settings);
      assertFalse(probe.projectionConfigured());
      assertTrue(server.requests.isEmpty());
      assertNull(probe.test(configuration(), TextModelRole.EMBEDDING));
      assertEquals(1, server.requests.size());
      assertEquals("/embeddings", server.requests.getFirst().path());
      assertEquals(
          "projection_configuration_required",
          probe.test(configuration(), TextModelRole.PROJECTION));
      assertEquals(1, server.requests.size());
      assertEquals(
          "projection_configuration_required",
          assertThrows(
                  ApplicationException.class, () -> settings.adapters(configuration(), "org-main"))
              .code());
      assertEquals(1, server.requests.size());
    }
  }

  @Test
  void legacyMediaConditionNeedsTheEntireOldGraphAndDoesNotUseNewDraftAsPlaceholder() {
    var environment = local();
    assertFalse(ManagedTextSettings.legacyAvailable(environment));
    environment
        .withProperty("RAG_EMBEDDING_MODEL", "fixture-embedding")
        .withProperty("RAG_EMBEDDING_API_KEY", "synthetic-legacy-key");
    assertFalse(ManagedTextSettings.legacyAvailable(environment));
    completeLegacy(environment);
    assertTrue(ManagedTextSettings.legacyAvailable(environment));
    assertEquals(2, ManagedTextSettings.legacy(environment, "org-main").projection().dimension());
    environment.withProperty("RAG_RERANK_API_KEY", "");
    assertFalse(ManagedTextSettings.legacyAvailable(environment));
    environment.withProperty("rag.model-configuration.enabled", "false");
    assertTrue(
        ManagedTextSettings.legacyAvailable(environment),
        "Legacy disabled-managed mode retains its original validation path");
  }

  @Test
  void liveCapabilitiesKeepSixFieldWireShapeAndDoNotClaimMissingLegacyBeans() {
    var active = new AtomicBoolean();
    var compatible = new AtomicBoolean(true);
    var base = capabilities();
    var runtime =
        new RuntimeService(
            () ->
                RuntimeConfiguration.managedCapabilities(
                    base, active.get(), compatible.get(), false, false, false));
    var controller = new RuntimeController(runtime);
    var missing = controller.configuration();
    assertTrue(missing.capabilities().contains("model_configuration"));
    assertFalse(missing.capabilities().contains("text_index"));
    assertFalse(missing.capabilities().contains("retrieval_test"));
    assertTrue(missing.unavailable().contains("retrieval_test"));
    active.set(true);
    var configured = controller.configuration();
    assertTrue(
        configured
            .capabilities()
            .containsAll(
                List.of("text_index", "indexings", "answers", "sources", "retrieval_test")));
    assertFalse(configured.capabilities().contains("visual_answers"));
    assertFalse(configured.capabilities().contains("video_answers"));
    assertFalse(configured.capabilities().contains("query_attachments"));
    assertTrue(
        configured
            .capabilities()
            .containsAll(List.of("sound_answers", "video_av_answers", "file_synopsis")));
    assertEquals("org-main", configured.workspaceId());
    assertEquals("java", configured.edition());
    var json = JsonMapper.builder().build().valueToTree(configured);
    assertEquals(
        Set.of(
            "auth_mode",
            "workspace_id",
            "edition",
            "migration_stage",
            "capabilities",
            "unavailable"),
        new HashSet<>(json.propertyNames()));
    active.set(false);
    assertFalse(controller.configuration().capabilities().contains("answers"));
    assertTrue(
        base.capabilities().contains("answers"),
        "Filtering must not mutate the original legacy snapshot");
  }

  @Test
  void actualLegacyBeansRequireMatchingProfileWhileIndependentFeaturesRemainAvailable() {
    var base = capabilities();
    var matching = RuntimeConfiguration.managedCapabilities(base, true, true, true, true, true);
    assertTrue(
        matching
            .capabilities()
            .containsAll(
                List.of(
                    "visual_answers",
                    "video_answers",
                    "query_attachments",
                    "image_vector_retrieval",
                    "audio_vector_retrieval")));
    var drifted = RuntimeConfiguration.managedCapabilities(base, true, false, true, true, true);
    for (String unavailable :
        List.of(
            "visual_answers",
            "video_answers",
            "query_attachments",
            "image_vector_retrieval",
            "audio_vector_retrieval")) {
      assertFalse(drifted.capabilities().contains(unavailable));
      assertTrue(drifted.unavailable().contains(unavailable));
    }
    assertTrue(
        drifted
            .capabilities()
            .containsAll(
                List.of(
                    "sound_answers",
                    "video_av_answers",
                    "file_synopsis",
                    "answers",
                    "retrieval_test")));
    assertTrue(
        new HashSet<>(drifted.capabilities()).stream().noneMatch(drifted.unavailable()::contains));
  }

  @Test
  void privateSettingsLiveBesideOwnedWorkOutsideSqliteAndSnapshots() {
    try (var store = new SqliteAuthorityStore(directory);
        var repository =
            new ModelConfigurationConfiguration().modelConfigurationRepository(store, local())) {
      repository.save(0, configuration());
      Path expected =
          store.libraryPath().getParent().resolve("private-model-settings/text-models.json");
      assertTrue(Files.isRegularFile(expected));
      assertFalse(
          Files.exists(store.operationGate().managedRoot().resolve("private-model-settings")));
      assertEquals(store.libraryPath().getParent(), expected.getParent().getParent());
      assertEquals(1, repository.read().version());
    }
  }

  @Test
  void malformedPrivateConfigurationCannotBootstrapOverItsExistingBytes() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      Path file = ManagedTextSettings.privateFile(store.libraryPath().getParent());
      Files.createDirectories(
          file.getParent(),
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      byte[] broken =
          "{\"format\":\"truncated-synthetic-private-file\"".getBytes(StandardCharsets.UTF_8);
      Files.write(file, broken);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      var environment = local();
      completeLegacy(environment);
      try (var repository =
          new ModelConfigurationConfiguration().modelConfigurationRepository(store, environment)) {
        assertEquals(
            "model_configuration_unavailable",
            assertThrows(ApplicationException.class, repository::read).code());
        assertArrayEquals(broken, Files.readAllBytes(file));
      }
    }
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.workspace-id", "org-main")
        .withProperty("rag.model-configuration.enabled", "true")
        .withProperty("rag.model-configuration.administrators", "owner");
  }

  private static void completeLegacy(MockEnvironment environment) {
    for (String role : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      environment
          .withProperty("RAG_" + role + "_BASE_URL", "https://synthetic.invalid/v1")
          .withProperty("RAG_" + role + "_MODEL", "fixture-" + role.toLowerCase(Locale.ROOT))
          .withProperty("RAG_" + role + "_API_KEY", "synthetic-legacy-key");
    }
    environment
        .withProperty("RAG_EMBEDDING_DIMENSIONS", "2")
        .withProperty("RAG_EMBEDDING_REVISION", "fixture-v1")
        .withProperty("RAG_MILVUS_ENDPOINT", "https://synthetic-projection.invalid")
        .withProperty("RAG_MILVUS_TOKEN", "synthetic-legacy-token")
        .withProperty("RAG_MILVUS_COLLECTION", "java_managed_legacy_fixture");
  }

  private static TextModelConfiguration configuration() {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            "fixture-embedding", "synthetic-setup-key", 2, "fixture-v1"),
        new TextModelConfiguration.Role("fixture-rerank", "synthetic-setup-key"),
        new TextModelConfiguration.Role("fixture-generation", "synthetic-setup-key"));
  }

  private static RuntimeCapabilities capabilities() {
    return new RuntimeCapabilities(
        "development_headers",
        "org-main",
        "text_answers",
        List.of(
            "text_upload",
            "ingestions",
            "text_index",
            "indexings",
            "answers",
            "sources",
            "source_image_content",
            "visual_image_upload",
            "visual_answers",
            "visual_sources",
            "video_upload",
            "video_index",
            "video_answers",
            "video_sources",
            "query_attachments",
            "image_vector_retrieval",
            "audio_vector_retrieval",
            "sound_answers",
            "video_av_answers",
            "file_synopsis"),
        List.of("production_readiness"));
  }
}
