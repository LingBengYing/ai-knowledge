package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorSchemaTest {
  @TempDir Path directory;

  @Test
  void freshFormatAddsCompleteImmutableMetadataOnlySidecars() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            assertEquals(
                14,
                store.count("SELECT COUNT(*) FROM pragma_table_info('audio_vector_publications')"));
            assertEquals(
                9, store.count("SELECT COUNT(*) FROM pragma_table_info('audio_vector_entries')"));
            assertEquals(
                14,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'audio_vector_%'"));
            assertEquals(
                8,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('audio_vector_publications_identity','audio_vector_publications_no_replace','audio_vector_publications_no_update','audio_vector_publications_no_delete','audio_vector_entries_sealed','audio_vector_entries_no_replace','audio_vector_entries_no_update','audio_vector_entries_no_delete')"));
            for (String table :
                new String[] {"audio_vector_publications", "audio_vector_entries"}) {
              assertEquals(
                  0,
                  store.count(
                      "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name IN ('pcm','wav','transcript','text','vector','key','endpoint')",
                      table));
            }
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }
}
