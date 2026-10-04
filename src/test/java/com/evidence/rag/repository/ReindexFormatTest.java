package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Observable format contract; it compiles against the unchanged v23 public Store API. */
class ReindexFormatTest {
  @TempDir Path directory;

  @Test
  void openingTheLibraryProducesTheRebuildCapableVersionTwentyFour() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(25, store.count("SELECT version FROM format_info"));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name IN ('rebuild_sequence','base_publication_id')"));
            assertEquals(1, store.count("PRAGMA foreign_keys"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }
}
