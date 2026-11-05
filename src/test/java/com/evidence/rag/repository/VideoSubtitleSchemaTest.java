package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoSubtitleSchemaTest {

  @TempDir Path directory;

  @Test
  void newStoreHasAllSubtitleSidecarsAndSynopsisKind() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            for (String table :
                List.of(
                    "video_subtitle_compilations",
                    "video_subtitle_tracks",
                    "video_subtitle_cues",
                    "video_subtitle_publication_entries",
                    "video_subtitle_trace_evidence")) {
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table));
            }
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('synopsis_input_evidence') WHERE name='video_subtitle_cue_id'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void independentSubtitleTablesAndCompleteSealsExistWithoutEnablingRuntime() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                3,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('corpus_revision_video_subtitle_complete','index_publication_video_subtitle_complete','video_subtitle_trace_evidence_identity')"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='synopsis_input_evidence' AND sql LIKE '%VIDEO_SUBTITLE%' AND sql LIKE '%ordinal BETWEEN 0 AND 4095%'"));
            return null;
          });
    }
  }
}
