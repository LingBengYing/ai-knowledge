package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.model.domain.TextModelConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelConfigurationRepositoryBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "root_array",
        "format_type",
        "format_version",
        "role_shape",
        "secret_type",
        "dimensions",
        "fractional_version",
        "negative_version",
        "overflow_version",
        "unsafe_integer_version",
        "trailing_json",
        "oversize"
      })
  void corruptSavedConfigurationCannotBootstrapOrOverwriteItsOriginalBytes(String corruption)
      throws Exception {
    Path file = directory.resolve("private/models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, configuration("saved-synthetic-key"));
      repository.activate(1);
      String original = Files.readString(file);
      String changed =
          switch (corruption) {
            case "root_array" -> "[]";
            case "format_type" ->
                original.replace("\"format\":\"java-text-configuration-v5\"", "\"format\":1");
            case "format_version" ->
                original.replace("java-text-configuration-v5", "java-text-configuration-v99");
            case "role_shape" ->
                original.replace("\"model\":\"rerank-model\"", "\"unexpected\":\"rerank-model\"");
            case "secret_type" ->
                original.replace("\"apiKey\":\"saved-synthetic-key\"", "\"apiKey\":42");
            case "dimensions" -> original.replace("\"dimensions\":2", "\"dimensions\":8193");
            case "fractional_version" -> original.replace("\"version\":1", "\"version\":1.5");
            case "negative_version" -> original.replace("\"version\":1", "\"version\":-1");
            case "overflow_version" ->
                original.replace("\"version\":1", "\"version\":9223372036854775808");
            case "unsafe_integer_version" ->
                original.replace("\"version\":1", "\"version\":9007199254740992");
            case "trailing_json" -> original + " {}";
            case "oversize" -> "x".repeat(131073);
            default -> throw new AssertionError(corruption);
          };
      assertFalse(changed.equals(original));
      byte[] saved = changed.getBytes(StandardCharsets.UTF_8);
      Files.write(file, saved);
      assertUnavailable(assertThrows(ApplicationException.class, repository::read));
      assertUnavailable(
          assertThrows(
              ApplicationException.class,
              () -> repository.bootstrapIfAbsent(configuration("fallback-synthetic-key"))));
      assertUnavailable(
          assertThrows(
              ApplicationException.class,
              () -> repository.save(1, configuration("replacement-synthetic-key"))));
      assertArrayEquals(saved, Files.readAllBytes(file));
    }
  }

  @Test
  void deletedPreviouslyInitializedFileIsNotTreatedAsAFirstRunAfterRestart() throws Exception {
    Path file = directory.resolve("private/models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, configuration("saved-synthetic-key"));
      repository.activate(1);
      Files.delete(file);
      assertUnavailable(assertThrows(ApplicationException.class, repository::read));
      assertUnavailable(
          assertThrows(
              ApplicationException.class,
              () -> repository.bootstrapIfAbsent(configuration("fallback-synthetic-key"))));
      assertFalse(Files.exists(file));
    }
    try (var reopened = new ModelConfigurationRepository(file)) {
      assertUnavailable(assertThrows(ApplicationException.class, reopened::read));
      assertFalse(Files.exists(file));
    }
  }

  @Test
  void privateDirectoryPermissionFailureLeavesBothDraftAndActiveFileUnchanged() throws Exception {
    Path file = directory.resolve("private/models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, configuration("active-synthetic-key"));
      repository.activate(1);
      repository.save(1, configuration("draft-synthetic-key"));
      byte[] before = Files.readAllBytes(file);
      Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-x------"));
      try {
        assertUnavailable(assertThrows(ApplicationException.class, () -> repository.activate(2)));
        assertUnavailable(
            assertThrows(
                ApplicationException.class,
                () -> repository.save(2, configuration("replacement-synthetic-key"))));
      } finally {
        Files.setPosixFilePermissions(
            file.getParent(), PosixFilePermissions.fromString("rwx------"));
      }
      assertArrayEquals(before, Files.readAllBytes(file));
      assertEquals(1L, repository.read().activeVersion());
      assertEquals(2, repository.read().version());
      assertEquals("active-synthetic-key", repository.read().active().generation().apiKey());
      assertEquals("draft-synthetic-key", repository.read().draft().generation().apiKey());
      try (var files = Files.list(file.getParent())) {
        assertEquals(
            0, files.filter(path -> path.getFileName().toString().endsWith(".partial")).count());
      }
    }
  }

  @Test
  void privateStorageRejectsDirectoryAndParentSymlinkSubstitutionWithoutTouchingTheirTargets()
      throws Exception {
    Path file = directory.resolve("private/models.json");
    byte[] saved;
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, configuration("saved-synthetic-key"));
      saved = Files.readAllBytes(file);
      Files.delete(file);
      Files.createDirectory(file);
      assertUnavailable(assertThrows(ApplicationException.class, repository::read));
      assertTrue(Files.isDirectory(file));
    }
    Path real = directory.resolve("real-private");
    Files.createDirectory(
        real, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path target = real.resolve("models.json");
    Files.write(target, saved);
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
    Path alias = directory.resolve("linked-private");
    Files.createSymbolicLink(alias, real.toAbsolutePath());
    assertUnavailable(
        assertThrows(
            ApplicationException.class,
            () -> new ModelConfigurationRepository(alias.resolve("models.json"))));
    assertArrayEquals(saved, Files.readAllBytes(target));
    assertTrue(Files.isSymbolicLink(alias));
  }

  @Test
  void aLockPathReplacedByADirectoryCannotBeTreatedAsAUsablePrivateWriter() throws Exception {
    Path parent = directory.resolve("private");
    Files.createDirectory(
        parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path lock = parent.resolve("models.json.lock");
    Files.createDirectory(
        lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    assertUnavailable(
        assertThrows(
            ApplicationException.class,
            () -> new ModelConfigurationRepository(parent.resolve("models.json"))));
    assertTrue(Files.isDirectory(lock));
    assertFalse(Files.exists(parent.resolve("models.json")));
  }

  @Test
  void noSavedDraftCannotBeActivatedAndClosedWriterCannotMutateItsCommittedActiveFile()
      throws Exception {
    Path file = directory.resolve("private/models.json");
    var repository = new ModelConfigurationRepository(file);
    assertEquals(
        "configuration_conflict",
        assertThrows(ApplicationException.class, () -> repository.activate(0)).code());
    assertNull(repository.read().activeVersion());
    repository.save(0, configuration("saved-synthetic-key"));
    repository.activate(1);
    byte[] before = Files.readAllBytes(file);
    repository.close();
    assertUnavailable(assertThrows(ApplicationException.class, repository::read));
    assertUnavailable(
        assertThrows(
            ApplicationException.class,
            () -> repository.save(1, configuration("replacement-synthetic-key"))));
    repository.close();
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void versionExhaustionCannotWrapAroundOrOverwriteTheSavedActiveConfiguration() throws Exception {
    Path file = directory.resolve("private/models.json");
    try (var repository = new ModelConfigurationRepository(file)) {
      repository.save(0, configuration("saved-synthetic-key"));
      repository.activate(1);
      String exhausted =
          Files.readString(file)
              .replace("\"version\":1", "\"version\":" + ModelConfigurationState.MAX_VERSION);
      Files.writeString(file, exhausted);
      byte[] before = Files.readAllBytes(file);
      assertEquals(ModelConfigurationState.MAX_VERSION, repository.read().version());
      assertEquals(1L, repository.read().activeVersion());
      assertUnavailable(
          assertThrows(
              ApplicationException.class,
              () ->
                  repository.save(
                      ModelConfigurationState.MAX_VERSION,
                      configuration("replacement-synthetic-key"))));
      assertArrayEquals(before, Files.readAllBytes(file));
    }
  }

  private static TextModelConfiguration configuration(String key) {
    return ModelConfigurationRepositoryTest.configuration(key);
  }

  private static void assertUnavailable(ApplicationException failure) {
    assertEquals("model_configuration_unavailable", failure.code());
    assertFalse(failure.toString().contains("synthetic-key"));
  }
}
