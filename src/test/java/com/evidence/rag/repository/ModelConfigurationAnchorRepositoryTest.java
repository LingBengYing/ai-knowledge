package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ModelConfigurationAnchorRepositoryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void firstActivationWritesExactV2AnchorWithoutCredentialDuplicationAndRestarts()
      throws Exception {
    Path file = directory.resolve("private/models.json");
    var roles = roles("generation-one", "rerank-one", "original-synthetic-key");
    var anchor = anchor(1, roles);
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, roles);
      var active = repository.activate(1, anchor);
      assertEquals(anchor, active.indexAnchor());
      JsonNode saved = JSON.readTree(Files.readAllBytes(file));
      assertEquals("java-text-configuration-v2", saved.path("format").stringValue());
      assertEquals(
          Set.of("format", "version", "draft", "active_version", "active", "index_anchor"),
          Set.copyOf(saved.propertyNames()));
      JsonNode basis = saved.path("index_anchor");
      assertEquals(
          Set.of(
              "originating_version",
              "provider_base_url",
              "embedding_model",
              "embedding_revision",
              "dimensions",
              "rerank_model",
              "generation_model",
              "target"),
          Set.copyOf(basis.propertyNames()));
      assertEquals(
          Set.of("embedding_identity", "projection_identity", "model_revision", "dimensions"),
          Set.copyOf(basis.path("target").propertyNames()));
      assertFalse(basis.toString().contains("synthetic-key"));
      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
    try (var repository = new ModelConfigurationRepository(file)) {
      assertEquals(anchor, repository.read().indexAnchor());
      assertEquals(roles, repository.read().active());
    }
  }

  @Test
  void successiveRoleAndKeyChangesRetainOriginalAnchorAndSavedActive() throws Exception {
    Path file = directory.resolve("models.json");
    var original = roles("generation-one", "rerank-one", "first-synthetic-key");
    var generation = roles("generation-two", "rerank-one", "second-synthetic-key");
    var rerank = roles("generation-two", "rerank-two", "third-synthetic-key");
    var anchor = anchor(1, original);
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      repository.activate(1, anchor);
      var draft = repository.save(1, generation);
      assertEquals(original, draft.active());
      assertEquals(anchor, draft.indexAnchor());
      repository.activate(2, anchor);
      repository.save(2, rerank);
      repository.activate(3, anchor);
    }
    try (var repository = new ModelConfigurationRepository(file)) {
      assertEquals(3L, repository.read().activeVersion());
      assertEquals(rerank, repository.read().active());
      assertEquals(anchor, repository.read().indexAnchor());
    }
  }

  @Test
  void v1ReadDoesNotRewriteAndUpgradeBindsSavedActiveInsteadOfCurrentDraft() throws Exception {
    Path file = directory.resolve("models.json");
    var original = roles("generation-one", "rerank-one", "first-synthetic-key");
    var changed = roles("generation-two", "rerank-one", "second-synthetic-key");
    byte[] v1;
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      repository.activate(1);
      repository.save(1, changed);
      v1 = Files.readAllBytes(file);
    }
    try (var repository = new ModelConfigurationRepository(file)) {
      assertNull(repository.read().indexAnchor());
      assertArrayEquals(v1, Files.readAllBytes(file));
      assertEquals(
          "configuration_conflict",
          assertThrows(ApplicationException.class, () -> repository.activate(2, anchor(2, changed)))
              .code());
      assertArrayEquals(v1, Files.readAllBytes(file));
      repository.activate(2, anchor(1, original));
      assertEquals(changed, repository.read().active());
      assertEquals(anchor(1, original), repository.read().indexAnchor());
    }
  }

  @Test
  void firstActivationRejectsInventedOriginAndRoleBasisWithoutWriting() throws Exception {
    Path file = directory.resolve("models.json");
    var original = roles("generation-one", "rerank-one", "synthetic-key");
    var invented = roles("generation-guessed", "rerank-one", "synthetic-key");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      byte[] before = Files.readAllBytes(file);
      assertEquals(
          "configuration_conflict",
          assertThrows(
                  ApplicationException.class, () -> repository.activate(1, anchor(1, invented)))
              .code());
      assertEquals(
          "configuration_conflict",
          assertThrows(
                  ApplicationException.class, () -> repository.activate(1, anchor(2, original)))
              .code());
      assertArrayEquals(before, Files.readAllBytes(file));
      assertNull(repository.read().active());
    }
  }

  @Test
  void anchoredActivationRejectsReplacementErasureAndStaleCasWithoutWriting() throws Exception {
    Path file = directory.resolve("models.json");
    var original = roles("generation-one", "rerank-one", "synthetic-key");
    var anchor = anchor(1, original);
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      repository.activate(1, anchor);
      repository.save(1, roles("generation-two", "rerank-one", "rotated-synthetic-key"));
      byte[] before = Files.readAllBytes(file);
      var replacement =
          new TextIndexAnchor(
              1,
              anchor.providerBaseUrl(),
              anchor.embeddingModel(),
              anchor.embeddingRevision(),
              2,
              anchor.rerankModel(),
              anchor.generationModel(),
              new IndexTarget("embedding-v1", "another-projection", "full-v1", 2));
      assertEquals(
          "configuration_conflict",
          assertThrows(ApplicationException.class, () -> repository.activate(2, replacement))
              .code());
      assertEquals(
          "configuration_conflict",
          assertThrows(ApplicationException.class, () -> repository.activate(2)).code());
      assertEquals(
          "configuration_conflict",
          assertThrows(ApplicationException.class, () -> repository.activate(1, anchor)).code());
      assertArrayEquals(before, Files.readAllBytes(file));
      assertEquals(original, repository.read().active());
      assertEquals(anchor, repository.read().indexAnchor());
    }
  }

  @Test
  void incompatibleEmbeddingDraftCanBeSavedButCannotBeActivatedWithOldAnchor() throws Exception {
    Path file = directory.resolve("models.json");
    var original = roles("generation-one", "rerank-one", "synthetic-key");
    var anchor = anchor(1, original);
    var changed =
        new TextModelConfiguration(
            new TextModelConfiguration.Embedding("other-embedding", "synthetic-key", 2, "v1"),
            original.rerank(),
            original.generation());
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      repository.activate(1, anchor);
      repository.save(1, changed);
      byte[] before = Files.readAllBytes(file);
      assertThrows(ApplicationException.class, () -> repository.activate(2, anchor));
      assertArrayEquals(before, Files.readAllBytes(file));
      assertEquals(original, repository.read().active());
      assertEquals(changed, repository.read().draft());
    }
  }

  @Test
  void unsafePrivateDirectoryPreservesOldActiveAndAnchor() throws Exception {
    Path file = directory.resolve("private/models.json");
    var original = roles("generation-one", "rerank-one", "synthetic-key");
    var anchor = anchor(1, original);
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      repository.activate(1, anchor);
      repository.save(1, roles("generation-two", "rerank-one", "rotated-synthetic-key"));
      byte[] before = Files.readAllBytes(file);
      Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwxr-x---"));
      assertEquals(
          "model_configuration_unavailable",
          assertThrows(ApplicationException.class, () -> repository.activate(2, anchor)).code());
      Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
      assertArrayEquals(before, Files.readAllBytes(file));
      assertEquals(original, repository.read().active());
      assertEquals(anchor, repository.read().indexAnchor());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "null",
        "extra",
        "fractional_origin",
        "future_origin",
        "dimension_mismatch",
        "extra_target",
        "embedded_key",
        "origin_role_mismatch",
        "unknown_format"
      })
  void malformedV2NeverFallsBackToBootstrapOrGuessesAnchor(String corruption) throws Exception {
    Path file = directory.resolve("models.json");
    var original = roles("generation-one", "rerank-one", "synthetic-key");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, original);
      repository.activate(1, anchor(1, original));
      ObjectNode root = (ObjectNode) JSON.readTree(Files.readAllBytes(file));
      ObjectNode basis = (ObjectNode) root.path("index_anchor");
      switch (corruption) {
        case "missing" -> {
          root.remove("index_anchor");
        }
        case "null" -> {
          root.putNull("index_anchor");
        }
        case "extra" -> {
          basis.put("untrusted", "private-value");
        }
        case "fractional_origin" -> {
          basis.put("originating_version", 1.5);
        }
        case "future_origin" -> {
          basis.put("originating_version", 2);
        }
        case "dimension_mismatch" -> {
          ((ObjectNode) basis.path("target")).put("dimensions", 3);
        }
        case "extra_target" -> {
          ((ObjectNode) basis.path("target")).put("key", "private-value");
        }
        case "embedded_key" -> {
          basis.put("provider_base_url", "https://private-value@provider.invalid/v1");
        }
        case "origin_role_mismatch" -> {
          basis.put("generation_model", "guessed-generation");
        }
        case "unknown_format" -> {
          root.put("format", "java-text-configuration-v3");
        }
        default -> {
          throw new AssertionError(corruption);
        }
      }
      Files.write(file, JSON.writeValueAsBytes(root));
      byte[] corrupted = Files.readAllBytes(file);
      var failed = assertThrows(ApplicationException.class, repository::read);
      assertEquals("model_configuration_unavailable", failed.code());
      assertFalse(failed.toString().contains("private-value"));
      assertThrows(ApplicationException.class, () -> repository.bootstrapIfAbsent(original));
      assertArrayEquals(corrupted, Files.readAllBytes(file));
      assertTrue(Files.exists(file));
    }
  }

  private static TextModelConfiguration roles(String generation, String rerank, String key) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding("embedding-one", key, 2, "v1"),
        new TextModelConfiguration.Role(rerank, key),
        new TextModelConfiguration.Role(generation, key));
  }

  private static TextIndexAnchor anchor(long version, TextModelConfiguration roles) {
    return new TextIndexAnchor(
        version,
        "https://api.siliconflow.cn/v1",
        roles.embedding().model(),
        roles.embedding().revision(),
        roles.embedding().dimensions(),
        roles.rerank().model(),
        roles.generation().model(),
        new IndexTarget("embedding-v1", "projection-v1", "full-v1", 2));
  }
}
