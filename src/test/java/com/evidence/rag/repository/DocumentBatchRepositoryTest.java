package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentBatchRepositoryTest {
  @TempDir Path directory;

  @Test
  void aLaterJdbcBatchFailureRollsBackTheWholeManifest() {
    try (var store = new SqliteAuthorityStore(directory.resolve("rollback"))) {
      var repository = new DocumentBatchRepository(store);
      var valid =
          new com.evidence.rag.model.domain.BatchImportFile.Manifest("one.txt", 1, "a".repeat(64));
      var invalid = new com.evidence.rag.model.domain.BatchImportFile.Manifest("two.txt", 1, null);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.insert(
                        new com.evidence.rag.model.domain.Actor("org-main", "owner"),
                        "batch",
                        "request",
                        "b".repeat(64),
                        10,
                        "2026-01-01T00:00:00Z",
                        List.of(valid, invalid));
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_batches"));
            assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_files"));
            return null;
          });
    }
  }
}
