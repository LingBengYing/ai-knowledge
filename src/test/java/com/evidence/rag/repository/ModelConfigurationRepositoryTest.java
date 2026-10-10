package com.evidence.rag.repository;

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
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelConfigurationRepositoryTest {
  @TempDir Path directory;

  @Test
  void chineseAnchorAndSealedVersionSurviveRestartWithTheirExactProfile() throws Exception {
    var file = directory.resolve("chinese/models.json");
    var roles = configuration("synthetic-key");
    var anchor =
        new TextIndexAnchor(
            1,
            "https://api.siliconflow.cn/v1",
            roles.embedding().model(),
            roles.embedding().revision(),
            roles.embedding().dimensions(),
            roles.rerank().model(),
            roles.generation().model(),
            new IndexTarget(
                "fixture-embedding",
                "fixture-projection",
                "fixture-model",
                roles.embedding().dimensions()),
            "https://api.siliconflow.cn/v1",
            "https://api.siliconflow.cn/v1",
            "java_chinese_test",
            "chinese");
    String configSha;
    String anchorSha;
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, roles);
      repository.activate(1, anchor);
      var sealed = repository.seal(1, roles, anchor);
      configSha = sealed.configurationSha256();
      anchorSha = sealed.anchorSha256();
      assertTrue(Files.readString(file).contains("java-text-configuration-v6"));
    }
    try (var repository = new ModelConfigurationRepository(file)) {
      assertEquals(anchor, repository.read().indexAnchor());
      assertEquals(anchor, repository.sealed(1, configSha, anchorSha).anchor());
      assertEquals("chinese", repository.read().indexAnchor().projectionAnalyzer());
    }
  }

  @Test
  void draftRotationKeepsActiveSecretAndBothSnapshotsSurviveRestart() throws Exception {
    Path file = directory.resolve("private/models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      assertEquals(0, repository.read().version());
      assertNull(repository.read().activeVersion());
      repository.save(0, configuration("first-synthetic-key"));
      repository.activate(1);
      var next = repository.save(1, configuration("second-synthetic-key"));
      assertEquals("first-synthetic-key", next.active().embedding().apiKey());
      assertEquals("second-synthetic-key", next.draft().embedding().apiKey());
      assertFalse(next.toString().contains("synthetic-key"));
      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
      assertEquals(
          PosixFilePermissions.fromString("rwx------"),
          Files.getPosixFilePermissions(file.getParent()));
    }
    try (var reopened = new ModelConfigurationRepository(file)) {
      assertEquals(2, reopened.read().version());
      assertEquals(1L, reopened.read().activeVersion());
      assertEquals("first-synthetic-key", reopened.read().active().embedding().apiKey());
      assertEquals("second-synthetic-key", reopened.read().draft().embedding().apiKey());
    }
  }

  @Test
  void staleSaveAndConcurrentCasLeaveExactlyOneCommittedDraft() throws Exception {
    try (var repository = new ModelConfigurationRepository(directory.resolve("models.json"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> save(repository, "one-synthetic-key"));
      var second = executor.submit(() -> save(repository, "two-synthetic-key"));
      assertEquals(1, first.get() + second.get());
      assertEquals(1, repository.read().version());
      assertEquals(
          "configuration_conflict",
          assertThrows(ApplicationException.class, () -> repository.activate(0)).code());
      assertNull(repository.read().activeVersion());
    }
  }

  @Test
  void bootstrapNeverOverwritesPersistedDraftOrUsesEnvironmentAfterCorruption() throws Exception {
    Path file = directory.resolve("models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.bootstrapIfAbsent(configuration("bootstrap-synthetic-key"));
      assertNull(repository.read().activeVersion());
      repository.save(1, configuration("saved-synthetic-key"));
      assertEquals(
          "saved-synthetic-key",
          repository
              .bootstrapIfAbsent(configuration("other-synthetic-key"))
              .draft()
              .rerank()
              .apiKey());
      Files.writeString(file, "{\"private_value\":\"must-never-leak\"}");
      var failed =
          assertThrows(
              ApplicationException.class,
              () -> repository.bootstrapIfAbsent(configuration("fallback-synthetic-key")));
      assertEquals("model_configuration_unavailable", failed.code());
      assertFalse(failed.toString().contains("must-never-leak"));
    }
  }

  @Test
  void symlinkPermissionsDuplicateJsonAndSecondWriterAreRejected() throws Exception {
    Path file = directory.resolve("models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, configuration("synthetic-key"));
      assertThrows(ApplicationException.class, () -> new ModelConfigurationRepository(file));
      byte[] original = Files.readAllBytes(file);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
      assertThrows(ApplicationException.class, repository::read);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      Files.writeString(
          file,
          new String(original, java.nio.charset.StandardCharsets.UTF_8)
              .replace("\"version\":1", "\"version\":1,\"version\":1"));
      assertThrows(ApplicationException.class, repository::read);
      Files.delete(file);
      Path outside = directory.resolve("outside.json");
      Files.write(outside, original);
      Files.createSymbolicLink(file, outside);
      assertThrows(ApplicationException.class, repository::read);
      assertTrue(Files.isSymbolicLink(file));
    }
  }

  private static int save(ModelConfigurationRepository repository, String key) {
    try {
      repository.save(0, configuration(key));
      return 1;
    } catch (ApplicationException conflict) {
      assertEquals("configuration_conflict", conflict.code());
      return 0;
    }
  }

  static TextModelConfiguration configuration(String key) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding("embedding-model", key, 2, "pinned-v1"),
        new TextModelConfiguration.Role("rerank-model", key),
        new TextModelConfiguration.Role("generation-model", key));
  }
}
