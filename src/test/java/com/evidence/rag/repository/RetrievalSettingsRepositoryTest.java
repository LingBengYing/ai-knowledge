package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.RetrievalSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalSettingsRepositoryTest {
  @TempDir Path directory;

  @Test
  void defaultsAreReadOnlyAndSavedSettingsSurviveRestartWithVersionConflict() throws Exception {
    Path file = directory.resolve("new-library/retrieval-settings.json");
    var repository = new RetrievalSettingsRepository(file, "org");
    assertEquals(RetrievalSettings.defaults(), repository.read());
    assertFalse(Files.exists(file.getParent()));
    var requested = new RetrievalSettings(0, "full_text", "weighted", 0.25, 9, true, 1.75);
    var saved = repository.save(requested);
    assertEquals(new RetrievalSettings(1, "full_text", "weighted", 0.25, 9, true, 1.75), saved);
    assertEquals(saved, new RetrievalSettingsRepository(file, "org").read());
    byte[] before = Files.readAllBytes(file);
    var failure = assertThrows(ApplicationException.class, () -> repository.save(requested));
    assertEquals(FailureKind.CONFLICT, failure.kind());
    org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(file));
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(file.getParent()));
    try (var files = Files.list(file.getParent())) {
      assertEquals(List.of(file), files.toList());
    }
  }

  @Test
  void corruptOrForeignConfigurationIsNeverReplacedByDefaultsOrSave() throws Exception {
    Path file = directory.resolve("retrieval-settings.json");
    var repository = new RetrievalSettingsRepository(file, "org");
    repository.save(RetrievalSettings.defaults());
    String valid = Files.readString(file);
    for (String invalid :
        List.of(
            "{broken",
            valid + "{}",
            valid.replace("\"org\"", "\"other\""),
            valid.replace("\"version\":1", "\"version\":1,\"version\":2"),
            valid.replace("\"top_k\":5", "\"top_k\":21"),
            valid.replace("\"settings\":{", "\"settings\":{\"extra\":1,"),
            " ".repeat(16 * 1024 + 1))) {
      Files.writeString(file, invalid);
      byte[] before = Files.readAllBytes(file);
      assertEquals(
          FailureKind.UNAVAILABLE,
          assertThrows(ApplicationException.class, repository::read).kind());
      assertEquals(
          FailureKind.UNAVAILABLE,
          assertThrows(
                  ApplicationException.class, () -> repository.save(RetrievalSettings.defaults()))
              .kind());
      assertArrayEquals(before, Files.readAllBytes(file));
    }
  }

  @Test
  void separateInstancesPerformOneAtomicCompareAndSwap() throws Exception {
    Path file = directory.resolve("retrieval-settings.json");
    var one = new RetrievalSettingsRepository(file, "org");
    var two = new RetrievalSettingsRepository(file, "org");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> saveAfter(start, one, 3));
      var second = executor.submit(() -> saveAfter(start, two, 7));
      start.countDown();
      var outcomes = List.of(first.get(), second.get());
      assertEquals(1, outcomes.stream().filter("saved"::equals).count());
      assertEquals(1, outcomes.stream().filter("conflict"::equals).count());
      var current = one.read();
      assertEquals(1, current.version());
      assertTrue(current.topK() == 3 || current.topK() == 7);
      assertEquals(current, two.read());
    }
  }

  @Test
  void symlinkNeverReadsOrChangesItsTarget() throws Exception {
    Path actual = directory.resolve("actual.json");
    var original = new RetrievalSettingsRepository(actual, "org");
    original.save(RetrievalSettings.defaults());
    byte[] before = Files.readAllBytes(actual);
    Path linked = directory.resolve("linked.json");
    Files.createSymbolicLink(linked, actual);
    var repository = new RetrievalSettingsRepository(linked, "org");
    assertEquals(
        FailureKind.UNAVAILABLE, assertThrows(ApplicationException.class, repository::read).kind());
    assertEquals(
        FailureKind.UNAVAILABLE,
        assertThrows(
                ApplicationException.class, () -> repository.save(RetrievalSettings.defaults()))
            .kind());
    assertArrayEquals(before, Files.readAllBytes(actual));
    assertTrue(Files.isSymbolicLink(linked));
  }

  private static String saveAfter(
      CountDownLatch start, RetrievalSettingsRepository repository, int topK)
      throws InterruptedException {
    start.await();
    try {
      repository.save(new RetrievalSettings(0, "hybrid", "weighted", 0.5, topK, false, 0.5));
      return "saved";
    } catch (ApplicationException conflict) {
      assertEquals(FailureKind.CONFLICT, conflict.kind());
      return "conflict";
    }
  }
}
