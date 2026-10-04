package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Observable v25 contract; uses the unchanged public library-opening API. */
class ReindexVectorContinuationFormatTest {
  @TempDir Path directory;

  @Test
  void libraryProvidesImmutableMediaContinuationBindingsAndACompleteJobSnapshot() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(25, store.count("SELECT version FROM format_info"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='base_vector_set_sha256'"));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name IN ('image_vector_bindings','audio_vector_bindings')"));
            assertEquals(1, store.count("PRAGMA foreign_keys"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }
}
