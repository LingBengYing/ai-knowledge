package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.QueryTrace;
import com.evidence.rag.model.domain.QueryTraceAttachment;
import com.evidence.rag.model.domain.TraceDraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryTraceMigrationTest {
  static final List<String> TABLES =
      List.of(
          "query_trace_attachment_images", "query_trace_attachments", "query_trace_preparations");
  static final String SHA = "a".repeat(64);
  static final String OTHER = "b".repeat(64);
  @TempDir Path directory;

  @Test
  void newStoreAddsOnlyHashOnlyVersionSixteenSidecars() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(24, store.count("PRAGMA user_version"));
            assertEquals(24, store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table));
              assertEquals(
                  4,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?,?)",
                      table + "_sealed",
                      table + "_no_replace",
                      table + "_no_update",
                      table + "_no_delete"));
              assertEquals(
                  0,
                  store.count(
                      "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name IN ('question','text','filename','content','transcript','answer','bytes')",
                      table));
            }
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='query_traces_query_preparation_complete'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void versionFifteenUpgradePreservesOldTraceAndOneRestorableBackup() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      insert(store, "legacy", null);
    }
    restoreVersionFifteen(directory);
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(directory)) {
        store.transaction(
            () -> {
              assertEquals(24, store.count("PRAGMA user_version"));
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM query_traces WHERE id='legacy' AND question_sha256=?",
                      SHA));
              assertEquals(0, store.count("SELECT COUNT(*) FROM query_trace_preparations"));
              return null;
            });
      }
    }
    List<Path> backups;
    try (var paths = Files.list(directory)) {
      backups =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v15-before-v16-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    assertEquals(15, scalar(backups.getFirst(), "PRAGMA user_version"));
    assertEquals(
        1, scalar(backups.getFirst(), "SELECT COUNT(*) FROM query_traces WHERE id='legacy'"));
    assertEquals(
        0,
        scalar(
            backups.getFirst(),
            "SELECT COUNT(*) FROM sqlite_master WHERE name='query_trace_preparations'"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var store = new SqliteAuthorityStore(restored)) {
      store.transaction(
          () -> {
            assertEquals(24, store.count("PRAGMA user_version"));
            assertEquals(1, store.count("SELECT COUNT(*) FROM query_traces WHERE id='legacy'"));
            return null;
          });
    }
  }

  @Test
  void migrationFailureRollsBackWithoutChangingReleasedTraceHistory() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      insert(store, "legacy", null);
    }
    restoreVersionFifteen(directory);
    sql("CREATE TABLE query_trace_preparations(unexpected TEXT)");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertEquals(15, scalar(directory.resolve("java-library.db"), "PRAGMA user_version"));
    assertEquals(
        1, scalar(directory.resolve("java-library.db"), "SELECT COUNT(*) FROM query_traces"));
    assertEquals(
        0,
        scalar(
            directory.resolve("java-library.db"),
            "SELECT COUNT(*) FROM sqlite_master WHERE name='query_trace_attachments'"));
  }

  @Test
  void reopeningRefusesPartialSidecarsAndDowngradeNeverDiscardsRealAttachments() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      insert(store, "prepared", prepared());
    }
    assertThrows(AssertionError.class, () -> restoreVersionFifteen(directory));
    assertEquals(
        1,
        scalar(
            directory.resolve("java-library.db"), "SELECT COUNT(*) FROM query_trace_preparations"));
    sql("DROP TRIGGER query_trace_preparations_no_update");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  static QueryTrace prepared() {
    var manifest =
        new QueryAttachmentManifest(
            0,
            SHA,
            QueryAttachment.Kind.VIDEO,
            "video-compiler-v1",
            OTHER,
            19,
            4,
            List.of(SHA, OTHER),
            true);
    return new QueryTrace(
        SHA,
        "prepare-v1",
        "rank-v1",
        "prepared",
        null,
        OTHER,
        List.of(new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.VIDEO, manifest)));
  }

  static void insert(SqliteAuthorityStore store, String id, QueryTrace trace) {
    store.transaction(
        () -> {
          new EvidenceRepository(store)
              .insertTrace(
                  id,
                  new EvidenceScope(
                      new Actor("org", "owner"), DocumentSelection.allDocuments(), List.of()),
                  new TraceDraft(
                          SHA, null, "abstained", "insufficient_evidence", "m", "p", "g", List.of())
                      .withQueryTrace(trace),
                  List.of(),
                  List.of(),
                  List.of(),
                  "2026-09-20T09:00:00Z");
          return null;
        });
  }

  /** Test-only downgrade refuses nonempty query sidecars instead of deleting audit history. */
  static void restoreVersionFifteen(Path directory) throws SQLException {
    AudioVectorMigrationTest.restoreVersionSeventeen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int current;
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        current = version.getInt(1);
      }
      if (current <= 15) {
        return;
      }
      assertEquals(17, current);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM image_vector_publications")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "Cannot discard image vector publication history");
      }
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      for (String table : TABLES) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard query attachment trace history");
        }
      }
      statement.execute("DROP TABLE image_vector_publications");
      statement.execute("DROP TRIGGER query_traces_query_preparation_complete");
      for (String table : TABLES) {
        statement.execute("DROP TABLE " + table);
      }
      statement.execute("UPDATE format_info SET version=15");
      statement.execute("PRAGMA user_version=15");
      connection.commit();
    }
  }

  private void sql(String query) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute(query);
    }
  }

  private static int scalar(Path database, String query) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }
}
