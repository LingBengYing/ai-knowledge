package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only inverse refuses every actual rebuild; it restores the physical v23 indexing format. */
final class ReindexVersion23Fixture {
  private static final String INDEXING_TABLE =
      "CREATE TABLE indexing_jobs(id TEXT PRIMARY KEY,document_id TEXT NOT NULL UNIQUE REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64),parser_revision TEXT NOT NULL,embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),state TEXT NOT NULL CHECK(state IN ('queued','processing','indexed','failed','cancelled')),attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),claim_token_sha256 TEXT,projection_generation_id TEXT,created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IN ('indexing_failed','indexing_timeout','indexing_output_invalid','worker_interrupted','authorization_changed','index_configuration_changed')),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id),FOREIGN KEY(id,attempt,projection_generation_id) REFERENCES indexing_attempts(job_id,attempt,projection_generation_id),UNIQUE(id,document_id,revision_id),CHECK((state='processing' AND claim_token_sha256 IS NOT NULL AND length(claim_token_sha256)=64) OR (state!='processing' AND claim_token_sha256 IS NULL)),CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)),CHECK(state NOT IN ('processing','indexed') OR projection_generation_id IS NOT NULL),CHECK(state!='queued' OR projection_generation_id IS NULL))";
  private static final String INDEXING_IDENTITY =
      "CREATE TRIGGER indexing_identity BEFORE UPDATE OF id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,created_by,created_at ON indexing_jobs BEGIN SELECT RAISE(ABORT,'immutable indexing identity'); END";
  private static final List<String> NEW_OBJECTS =
      List.of(
          "indexing_one_pending",
          "indexing_rebuild_identity",
          "active_corpus_publication_rebuild_complete",
          "active_corpus_publication_initial");

  private ReindexVersion23Fixture() {}

  static void restoreVersionTwentyThree(Path directory) throws SQLException {
    ReindexVectorVersion24Fixture.restoreVersionTwentyFour(directory);
    Path database = directory.resolve("java-library.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      long version = scalar(connection, "PRAGMA user_version");
      if (version <= 23) {
        return;
      }
      assertEquals(24, version);
      assertEquals(
          0,
          scalar(
              connection,
              "SELECT COUNT(*) FROM indexing_jobs WHERE rebuild_sequence!=0 OR base_publication_id IS NOT NULL"));
      var objects =
          rows(
              connection,
              "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='indexing_jobs' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name");
      var columns =
          rows(
                  connection,
                  "SELECT name FROM pragma_table_info('indexing_jobs') WHERE name NOT IN ('rebuild_sequence','base_publication_id') ORDER BY cid")
              .stream()
              .map(row -> (String) row.get("name"))
              .toList();
      String names = String.join(",", columns);
      String updateGuard =
          (String)
              rows(
                      connection,
                      "SELECT sql FROM sqlite_master WHERE name='cleanup_schema_objects_no_update'")
                  .getFirst()
                  .get("sql");
      String deleteGuard =
          (String)
              rows(
                      connection,
                      "SELECT sql FROM sqlite_master WHERE name='cleanup_schema_objects_no_delete'")
                  .getFirst()
                  .get("sql");
      execute(connection, "PRAGMA foreign_keys=OFF");
      execute(connection, "PRAGMA legacy_alter_table=ON");
      execute(connection, "BEGIN IMMEDIATE");
      try {
        execute(
            connection,
            INDEXING_TABLE.replace(
                "CREATE TABLE indexing_jobs(", "CREATE TABLE indexing_jobs_v23_fixture("));
        execute(
            connection,
            "INSERT INTO indexing_jobs_v23_fixture("
                + names
                + ") SELECT "
                + names
                + " FROM indexing_jobs");
        assertEquals(
            scalar(connection, "SELECT COUNT(*) FROM indexing_jobs"),
            scalar(connection, "SELECT COUNT(*) FROM indexing_jobs_v23_fixture"));
        assertEquals(
            0,
            scalar(
                connection,
                "SELECT COUNT(*) FROM (SELECT "
                    + names
                    + " FROM indexing_jobs EXCEPT SELECT "
                    + names
                    + " FROM indexing_jobs_v23_fixture)"));
        execute(connection, "DROP TABLE indexing_jobs");
        execute(connection, "ALTER TABLE indexing_jobs_v23_fixture RENAME TO indexing_jobs");
        for (var object : objects) {
          String name = (String) object.get("name");
          if (NEW_OBJECTS.contains(name)) {
            continue;
          }
          execute(
              connection,
              "indexing_identity".equals(name) ? INDEXING_IDENTITY : (String) object.get("sql"));
        }
        for (String name :
            List.of(
                "active_corpus_publication_rebuild_complete",
                "active_corpus_publication_initial")) {
          execute(connection, "DROP TRIGGER " + name);
        }
        execute(connection, "DROP TRIGGER active_corpus_publications_no_update");
        execute(
            connection,
            "CREATE TRIGGER active_corpus_publications_no_update BEFORE UPDATE ON active_corpus_publications BEGIN SELECT RAISE(ABORT,'immutable index publication'); END");
        execute(connection, "DROP TRIGGER cleanup_schema_objects_no_update");
        execute(connection, "DROP TRIGGER cleanup_schema_objects_no_delete");
        execute(connection, "DELETE FROM cleanup_schema_objects");
        execute(connection, updateGuard);
        execute(connection, deleteGuard);
        for (var object :
            rows(
                connection,
                "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)")) {
            insert.setString(1, (String) object.get("name"));
            insert.setString(2, (String) object.get("type"));
            insert.setString(
                3,
                ModelValues.sha256(((String) object.get("sql")).getBytes(StandardCharsets.UTF_8)));
            assertEquals(1, insert.executeUpdate());
          }
        }
        execute(connection, "UPDATE format_info SET version=23");
        execute(connection, "PRAGMA user_version=23");
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        execute(connection, "COMMIT");
      } catch (SQLException | RuntimeException | Error failure) {
        execute(connection, "ROLLBACK");
        throw failure;
      } finally {
        execute(connection, "PRAGMA legacy_alter_table=OFF");
        execute(connection, "PRAGMA foreign_keys=ON");
      }
    }
  }

  static List<Map<String, Object>> rows(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      return rows(connection, sql);
    }
  }

  private static List<Map<String, Object>> rows(Connection connection, String sql)
      throws SQLException {
    var rows = new ArrayList<Map<String, Object>>();
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      while (result.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
          row.put(result.getMetaData().getColumnLabel(column), result.getObject(column));
        }
        rows.add(Collections.unmodifiableMap(row));
      }
    }
    return List.copyOf(rows);
  }

  static void execute(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      execute(connection, sql);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }
}
