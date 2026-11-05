package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVectorSchemaTest {
  @TempDir Path directory;

  @Test
  void freshFormatAddsOnlyImmutableBoundedSidecarMetadata() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            assertEquals(
                16,
                store.count("SELECT COUNT(*) FROM pragma_table_info('image_vector_publications')"));
            assertEquals(
                5,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'image_vector_publications_%'"));
            assertEquals(
                4,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('image_vector_publications_identity','image_vector_publications_no_replace','image_vector_publications_no_update','image_vector_publications_no_delete')"));
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('image_vector_publications') WHERE name IN ('content','caption','text','vector','key','endpoint')"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }
}
