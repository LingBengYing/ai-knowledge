package com.evidence.rag.worker.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.CleanupPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CleanupRestoreJournalTest {
  @TempDir Path directory;

  @Test
  void sameSealedIntentIsIdempotentAndPersistsOnlyExactHashIdentity() throws Exception {
    var plan = plan("cleanup-one");
    CleanupRestoreJournal.intent(directory, "library-one", plan);
    Path journal = directory.resolve(CleanupRestoreJournal.FILENAME);
    String before = Files.readString(journal);
    CleanupRestoreJournal.intent(directory, "library-one", plan);
    assertEquals(before, Files.readString(journal));
    var entry = tools.jackson.databind.json.JsonMapper.builder().build().readTree(before);
    assertEquals(5, entry.size());
    assertEquals(plan.manifestSha256(), entry.path("plan_sha256").textValue());
    assertEquals(plan.sourceSha256(), entry.path("source_sha256").textValue());
  }

  @Test
  void wrongLibraryOrChangedSealedPlanCannotOverwriteAnExistingDenial() throws Exception {
    CleanupRestoreJournal.intent(directory, "library-one", plan("cleanup-one"));
    Path journal = directory.resolve(CleanupRestoreJournal.FILENAME);
    String before = Files.readString(journal);
    assertThrows(
        IOException.class,
        () -> CleanupRestoreJournal.intent(directory, "library-two", plan("cleanup-two")));
    assertEquals(before, Files.readString(journal));
  }

  @Test
  void symbolicJournalDoesNotWriteAnUnmanagedTarget() throws Exception {
    Path outside = directory.resolve("unmanaged");
    Files.writeString(outside, "must stay unchanged");
    Files.createSymbolicLink(directory.resolve(CleanupRestoreJournal.FILENAME), outside);
    assertThrows(
        IOException.class,
        () -> CleanupRestoreJournal.intent(directory, "library-one", plan("cleanup-one")));
    assertEquals("must stay unchanged", Files.readString(outside));
  }

  @Test
  void symbolicLibraryDirectoryCannotRegisterAnIntentInItsTarget() throws Exception {
    Path target = Files.createDirectory(directory.resolve("target"));
    Path link = directory.resolve("linked-library");
    Files.createSymbolicLink(link, target);
    assertThrows(
        IOException.class,
        () -> CleanupRestoreJournal.intent(link, "library-one", plan("cleanup-one")));
    try (var files = Files.list(target)) {
      assertEquals(List.of(), files.toList());
    }
  }

  private static CleanupPlan plan(String cleanupId) {
    String source = "a".repeat(64);
    return new CleanupPlan(
        cleanupId,
        "document-one",
        "workspace-one",
        source,
        CleanupPlan.fingerprint(cleanupId, "document-one", "workspace-one", source, List.of()),
        List.of());
  }
}
