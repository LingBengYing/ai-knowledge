package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvQuerySchemaTest {
  @TempDir Path directory;

  @Test
  void freshLibraryHasTwoHashSidecarsAndAllTwentyFourMediaGuards() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            assertEquals(
                8,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('video_av_query_preparations')"));
            assertEquals(
                12,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('video_av_query_attachments')"));
            assertEquals(
                9,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'video_av_query_%'"));
            assertEquals(
                24,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'video_av_%' AND name NOT LIKE 'video_av_query_%'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void childrenThenHeaderThenParentSealCompletePreparedAndUnpreparedBatches() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            child(store, "prepared", 0, "prepared", "JOINT", "compiler-v1");
            child(store, "prepared", 1, "prepared", "JOINT", "compiler-v1");
            header(store, "prepared", 2, "JOINT");
            parent(store, "prepared", "JOINT", "a".repeat(64));
            child(store, "unprepared", 0, "not_prepared", "VISUAL", "compiler-v1");
            header(store, "unprepared", 1, "VISUAL");
            parent(store, "unprepared", "VISUAL", "a".repeat(64));
            assertEquals(3, store.count("SELECT COUNT(*) FROM video_av_query_attachments"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      for (String sql :
          List.of(
              "UPDATE video_av_query_attachments SET source_sha256='" + "b".repeat(64) + "'",
              "UPDATE video_av_query_preparations SET embedding_revision='changed'",
              "DELETE FROM video_av_query_attachments",
              "DELETE FROM video_av_query_preparations",
              "INSERT OR REPLACE INTO video_av_query_attachments SELECT * FROM video_av_query_attachments WHERE trace_id='prepared' AND ordinal=0",
              "INSERT OR REPLACE INTO video_av_query_preparations SELECT * FROM video_av_query_preparations WHERE trace_id='prepared'")) {
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute(sql);
                      return null;
                    }));
      }
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    child(store, "prepared", 2, "prepared", "JOINT", "compiler-v1");
                    return null;
                  }));
    }
  }

  @Test
  void incompleteMixedOrChangedBatchCannotCommitAnyPrefix() {
    try (var store = new SqliteAuthorityStore(directory)) {
      for (int failure = 0; failure < 6; failure++) {
        final int choice = failure;
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    () -> {
                      child(store, "bad", choice == 0 ? 1 : 0, "prepared", "JOINT", "compiler-v1");
                      if (choice == 1) {
                        child(store, "bad", 1, "not_prepared", "JOINT", "compiler-v1");
                      } else if (choice == 2) {
                        child(store, "bad", 1, "prepared", "AUDIO", "compiler-v1");
                      } else if (choice == 3) {
                        child(store, "bad", 1, "prepared", "JOINT", "compiler-v2");
                      }
                      header(store, "bad", choice >= 1 && choice <= 3 ? 2 : 1, "JOINT");
                      parent(
                          store,
                          "bad",
                          choice == 4 ? "AUDIO" : "JOINT",
                          choice == 5 ? "b".repeat(64) : "a".repeat(64));
                      return null;
                    }));
      }
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_av_query_attachments"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_av_query_preparations"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_av_traces"));
            return null;
          });
    }
  }

  @Test
  void noPartialPreparationNullBypassOrDanglingChildrenCanPersist() {
    try (var store = new SqliteAuthorityStore(directory)) {
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    child(store, "orphan", 0, "prepared", "VISUAL", "compiler-v1");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    child(store, "orphan", 0, "prepared", "VISUAL", "compiler-v1");
                    parent(store, "orphan", "VISUAL", "a".repeat(64));
                    return null;
                  }));
      for (String shape :
          List.of(
              "NULL,1,1,0,0,'VISUAL','prepared'",
              "'" + "a".repeat(64) + "',1.5,1,1,1,'JOINT','prepared'",
              "'" + "a".repeat(64) + "',NULL,1,0,0,'VISUAL','prepared'",
              "NULL,NULL,NULL,NULL,0,'VISUAL','not_prepared'",
              "'" + "a".repeat(64) + "',1,1,0,0,'JOINT','prepared'")) {
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute(
                          "INSERT INTO video_av_query_attachments VALUES('bad',0,?,'video','compiler-v1',"
                              + shape
                              + ")",
                          "a".repeat(64));
                      return null;
                    }));
      }
    }
  }

  private static void child(
      SqliteAuthorityStore store,
      String id,
      int ordinal,
      String status,
      String mode,
      String compiler) {
    boolean prepared = status.equals("prepared");
    store.execute(
        "INSERT INTO video_av_query_attachments VALUES(?,?,?,'video',?,?,?,?,?,?,?,?)",
        id,
        ordinal,
        "a".repeat(64),
        compiler,
        prepared ? "b".repeat(64) : null,
        prepared ? 1 : null,
        prepared ? 1 : null,
        prepared ? 1 : null,
        prepared ? 1 : null,
        mode,
        status);
  }

  private static void header(SqliteAuthorityStore store, String id, int count, String mode) {
    store.execute(
        "INSERT INTO video_av_query_preparations VALUES(?,?,?,?,?,?,?,?)",
        id,
        count,
        mode,
        "a".repeat(64),
        "b".repeat(64),
        "embedding-v1",
        "java-video-av-query-preparation-v1",
        "c".repeat(64));
  }

  private static void parent(SqliteAuthorityStore store, String id, String mode, String question) {
    store.execute(
        "INSERT INTO video_av_traces VALUES(?,'org','owner',0,0,0,?,?,NULL,'abstained','empty_scope','model-v1','java-video-av-answer-v1','now')",
        id,
        mode,
        question);
  }
}
