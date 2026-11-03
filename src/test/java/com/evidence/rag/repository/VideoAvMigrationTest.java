package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvMigrationTest {
  private static final List<String> TABLES =
      List.of(
          "video_av_trace_evidence",
          "video_av_trace_documents",
          "video_av_traces",
          "video_av_windows",
          "video_av_publications",
          "video_av_originals");
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void freshV20KeepsAllOldAuthorityAndAddsExactSixTablesAndDeferredChildren() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION,
                store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              assertEquals(0, store.count("SELECT COUNT(*) FROM " + table));
            }
            assertEquals(
                24,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'video_av_%' AND name NOT LIKE 'video_av_query_%'"));
            assertEquals(
                33, store.count("SELECT COUNT(*) FROM pragma_table_info('video_av_publications')"));
            assertEquals(
                19, store.count("SELECT COUNT(*) FROM pragma_table_info('video_av_windows')"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void v19UpgradeCreatesBackupWithoutChangingExistingMetadataOrSpeechState() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            new ManagementRepository(store)
                .insertDocument(
                    OWNER,
                    new SyntheticDocument(
                        "existing",
                        "old.txt",
                        "document",
                        "text/plain",
                        "old-rev",
                        "a".repeat(64),
                        1),
                    Instant.now().toString());
            return null;
          });
    }
    restoreVersionNineteen(directory);
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM documents WHERE id='existing' AND active_revision_id='old-rev' AND source_sha256=?",
                    "a".repeat(64)));
            assertEquals(0, store.count("SELECT COUNT(*) FROM sound_publications"));
            return null;
          });
    }
    try (var files = Files.list(directory)) {
      assertTrue(
          files.anyMatch(
              path -> path.getFileName().toString().startsWith("java-library.v19-before-v20-")));
    }
  }

  @Test
  void metadataIsImmutableAndOnlyTombstoneAllowsRawBlobErasure() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var original = register(store);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_originals SET original_blob=x''");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_originals SET filename='changed.mp4'");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    LegacyMediaRows.videoAvOriginal(store, original, Instant.now().toString());
                    return null;
                  }));
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO document_tombstones(document_id,workspace_id,requested_by,requested_at) VALUES(?,?,?,?)",
                original.documentId(),
                "org",
                "owner",
                Instant.now().toString());
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_originals SET original_blob=x''");
                    return null;
                  }));
      CleanupMaintenanceFixture.purge(store, OWNER, original.documentId());
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT length(original_blob) FROM video_av_originals"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE video_av_originals SET original_blob=?", original.content());
                    return null;
                  }));
    }
  }

  @Test
  void partiallyNullMediaGroupAndMissingHeaderCannotSurviveTransaction() {
    try (var store = new SqliteAuthorityStore(directory)) {
      register(store);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO video_av_windows(publication_id,id,ordinal,start_tick,end_tick,clip_sha256) VALUES(?,?,?,?,?,?)",
                        "unknown",
                        "av-" + "a".repeat(64),
                        0,
                        0,
                        10,
                        "b".repeat(64));
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO video_av_windows(publication_id,id,ordinal,start_tick,end_tick,clip_sha256,frame_count,frames_manifest_sha256,first_local_tick,end_local_tick,visual_physical_id,visual_entry_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        "unknown",
                        "av-" + "a".repeat(64),
                        0,
                        0,
                        10,
                        "b".repeat(64),
                        1,
                        "c".repeat(64),
                        0,
                        10,
                        "seg-" + "d".repeat(64),
                        "e".repeat(64));
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_av_windows"));
            return null;
          });
    }
  }

  @Test
  void missingGuardAndExtraColumnFailClosedOnReopen() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            store.execute("DROP TRIGGER video_av_windows_sealed");
            return null;
          });
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  private static DocumentOriginal register(SqliteAuthorityStore store) {
    byte[] raw = new byte[16];
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, raw, 4, 4);
    var original =
        new DocumentOriginal(
            "doc",
            "rev",
            "clip.mp4",
            "video",
            "video/mp4",
            ModelValues.sha256(raw),
            raw.length,
            raw);
    return store.transaction(
        () -> {
          var management = new ManagementRepository(store);
          management.insertDocument(
              OWNER,
              new SyntheticDocument(
                  original.documentId(),
                  original.filename(),
                  original.documentType(),
                  original.mediaType(),
                  original.revisionId(),
                  original.sourceSha256(),
                  original.sizeBytes()),
              Instant.now().toString());
          management.insertGrant(original.documentId(), "owner", "owner");
          LegacyMediaRows.videoAvOriginal(store, original, Instant.now().toString());
          return original;
        });
  }

  /** Test-only downgrade never discards new source, publication, or trace data. */
  static void restoreVersionNineteen(Path directory) throws SQLException {
    HistoricalSchemaV25Fixture.restoreVersionTwentyFive(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int version;
      try (var rows = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(rows.next());
        version = rows.getInt(1);
      }
      if (version <= 19) {
        return;
      }
      if (version == 24 || version == 25) {
        ReindexVersion23Fixture.restoreVersionTwentyThree(directory);
        version = 23;
      }
      if (version == 22 || version == 23) {
        CleanupV21Fixture.restoreVersionTwentyOne(directory);
        version = 21;
      }
      assertEquals(21, version);
      for (String table : TABLES) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard video audiovisual authority history");
        }
      }
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      for (String table : List.of("video_av_query_attachments", "video_av_query_preparations")) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard reference-query preparation history");
        }
        statement.execute("DROP TABLE " + table);
      }
      for (String table : TABLES) {
        statement.execute("DROP TABLE " + table);
      }
      statement.execute("UPDATE format_info SET version=19");
      statement.execute("PRAGMA user_version=19");
      connection.commit();
    }
  }
}
