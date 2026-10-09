package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalSchemaV25FixtureTest {
  @TempDir Path directory;

  @Test
  void restoresPhysicalVersionTwentyFiveAndRetainsOriginalPublicationRows() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      ReindexSqlFixture.publish(authority);
    }
    var database = directory.resolve("java-library.db");
    var documents = ReindexVersion23Fixture.rows(database, "SELECT * FROM documents ORDER BY id");
    var originals =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT document_id,hex(original_blob) FROM corpus_documents ORDER BY document_id");
    var publications =
        ReindexVersion23Fixture.rows(database, "SELECT * FROM index_publications ORDER BY id");
    HistoricalSchemaV25Fixture.restoreVersionTwentyFive(directory);
    assertEquals(25, count("PRAGMA user_version"));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('document_replacements','knowledge_answer_traces','model_rebuilds')"));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name IN ('replacement_id','model_rebuild_id')"));
    assertEquals(
        documents, ReindexVersion23Fixture.rows(database, "SELECT * FROM documents ORDER BY id"));
    assertEquals(
        originals,
        ReindexVersion23Fixture.rows(
            database,
            "SELECT document_id,hex(original_blob) FROM corpus_documents ORDER BY document_id"));
    assertEquals(
        publications,
        ReindexVersion23Fixture.rows(database, "SELECT * FROM index_publications ORDER BY id"));
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals(
          HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
      assertEquals(
          0, store.transaction(() -> store.count("SELECT COUNT(*) FROM pragma_foreign_key_check")));
    }
  }

  @Test
  void refusesPersistedKnowledgeTraceBeforeChangingFormatOrAnyAuthorityRows() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO knowledge_answer_traces VALUES('synthetic-trace','org','owner',0,0,0,?,NULL,'abstained','incomplete_evidence','model-v1','prompt-v1','policy-v1','2026-10-08T00:00:00Z')",
                "a".repeat(64));
            return null;
          });
    }
    var database = directory.resolve("java-library.db");
    var before = ReindexVersion23Fixture.rows(database, "SELECT * FROM knowledge_answer_traces");
    var schema =
        ReindexVersion23Fixture.rows(
            database, "SELECT type,name,sql FROM sqlite_master ORDER BY type,name");
    assertThrows(
        AssertionError.class, () -> HistoricalSchemaV25Fixture.restoreVersionTwentyFive(directory));
    assertEquals(HistoricalSchemaV25Fixture.CURRENT_VERSION, count("PRAGMA user_version"));
    assertEquals(
        before, ReindexVersion23Fixture.rows(database, "SELECT * FROM knowledge_answer_traces"));
    assertEquals(
        schema,
        ReindexVersion23Fixture.rows(
            database, "SELECT type,name,sql FROM sqlite_master ORDER BY type,name"));
  }

  @Test
  void refusesInitializedModelSelectionWithoutResettingItToAnOlderFormat() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            store.execute(
                "UPDATE text_runtime_selection SET initialized=1,updated_at='2026-10-08T00:00:00Z' WHERE id=1");
            return null;
          });
    }
    assertThrows(
        AssertionError.class, () -> HistoricalSchemaV25Fixture.restoreVersionTwentyFive(directory));
    assertEquals(HistoricalSchemaV25Fixture.CURRENT_VERSION, count("PRAGMA user_version"));
    assertEquals(1, count("SELECT initialized FROM text_runtime_selection WHERE id=1"));
  }

  private long count(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
