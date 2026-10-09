package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Test-only physical inverse: no continuation data may be discarded or merely relabeled. */
final class ReindexVectorVersion24Fixture {
  private ReindexVectorVersion24Fixture() {}

  private static final String REBUILD =
      "CREATE TRIGGER indexing_rebuild_identity BEFORE INSERT ON indexing_jobs\nWHEN NEW.rebuild_sequence>0 AND (NEW.state!='queued' OR NEW.attempt!=1 OR (\n    NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)\n    OR NOT EXISTS(SELECT 1 FROM active_corpus_publications a\n      JOIN index_publications p ON p.id=a.publication_id AND p.document_id=a.document_id AND p.revision_id=a.revision_id\n      JOIN indexing_jobs prior ON prior.id=p.job_id AND prior.state='indexed'\n      JOIN corpus_documents c ON c.document_id=a.document_id AND c.parsed_revision_id=a.revision_id\n      JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL\n      JOIN documents d ON d.id=c.document_id AND d.source_sha256=r.source_sha256\n      JOIN ingestion_jobs parsed ON parsed.document_id=c.document_id AND parsed.revision_id=r.id AND parsed.state='parsed'\n      WHERE a.document_id=NEW.document_id AND a.publication_id=NEW.base_publication_id\n        AND p.revision_id=NEW.revision_id AND p.source_sha256=NEW.source_sha256 AND p.parser_revision=NEW.parser_revision\n        AND p.embedding_identity=NEW.embedding_identity AND p.projection_identity=NEW.projection_identity\n        AND p.model_revision=NEW.model_revision AND p.dimensions=NEW.dimensions\n        AND r.source_sha256=NEW.source_sha256 AND r.parser_revision=NEW.parser_revision)\n    OR EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=NEW.base_publication_id)\n    OR EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=NEW.base_publication_id)\n    OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)))\nBEGIN SELECT RAISE(ABORT,'invalid rebuild identity'); END\n";
  private static final String ACTIVE =
      "CREATE TRIGGER active_corpus_publications_no_update BEFORE UPDATE ON active_corpus_publications\nWHEN NEW.document_id IS NOT OLD.document_id OR NEW.revision_id IS NOT OLD.revision_id\n  OR NOT EXISTS(SELECT 1 FROM index_publications p\n    JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'\n      AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id\n    JOIN index_publications base ON base.id=OLD.publication_id AND base.document_id=OLD.document_id AND base.revision_id=OLD.revision_id\n    WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id\n      AND j.rebuild_sequence>0 AND j.base_publication_id=OLD.publication_id\n      AND p.id!=base.id AND p.projection_generation_id!=base.projection_generation_id\n      AND p.source_sha256=base.source_sha256 AND p.parser_revision=base.parser_revision\n      AND p.embedding_identity=base.embedding_identity AND p.projection_identity=base.projection_identity\n      AND p.model_revision=base.model_revision AND p.dimensions=base.dimensions\n      AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)\n      AND NOT EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=OLD.publication_id)\n      AND NOT EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=OLD.publication_id))\nBEGIN SELECT RAISE(ABORT,'immutable index publication'); END\n";

  static void restoreVersionTwentyFour(Path directory) throws SQLException {
    // Refuse the complete inverse before changing any newer format marker or schema.
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"))) {
      if (scalar(connection, "PRAGMA user_version") > 25) {
        assertEquals(
            0,
            scalar(
                connection,
                "SELECT COUNT(*) FROM indexing_jobs WHERE base_vector_set_sha256 IS NOT NULL"));
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM image_vector_bindings"));
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM audio_vector_bindings"));
      }
    }
    HistoricalSchemaV25Fixture.restoreVersionTwentyFive(directory);
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"))) {
      long version = scalar(connection, "PRAGMA user_version");
      if (version <= 24) {
        return;
      }
      assertEquals(25, version);
      assertEquals(
          0,
          scalar(
              connection,
              "SELECT COUNT(*) FROM indexing_jobs WHERE base_vector_set_sha256 IS NOT NULL"));
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM image_vector_bindings"));
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM audio_vector_bindings"));
      String identity =
          string(connection, "SELECT sql FROM sqlite_master WHERE name='indexing_identity'")
              .replace(",base_vector_set_sha256 ON indexing_jobs", " ON indexing_jobs");
      String updateGuard =
          string(
              connection,
              "SELECT sql FROM sqlite_master WHERE name='cleanup_schema_objects_no_update'");
      String deleteGuard =
          string(
              connection,
              "SELECT sql FROM sqlite_master WHERE name='cleanup_schema_objects_no_delete'");
      execute(connection, "PRAGMA foreign_keys=OFF");
      execute(connection, "BEGIN IMMEDIATE");
      try {
        for (String name :
            List.of(
                "indexing_identity",
                "indexing_rebuild_identity",
                "active_corpus_publications_no_update",
                "indexing_vector_snapshot_initial",
                "image_vector_publications_no_binding_shadow",
                "audio_vector_publications_no_binding_shadow")) {
          execute(connection, "DROP TRIGGER " + name);
        }
        execute(connection, "DROP TABLE image_vector_bindings");
        execute(connection, "DROP TABLE audio_vector_bindings");
        execute(connection, "ALTER TABLE indexing_jobs DROP COLUMN base_vector_set_sha256");
        execute(connection, identity);
        execute(connection, REBUILD);
        execute(connection, ACTIVE);
        execute(connection, "DROP TRIGGER cleanup_schema_objects_no_update");
        execute(connection, "DROP TRIGGER cleanup_schema_objects_no_delete");
        execute(connection, "DELETE FROM cleanup_schema_objects");
        execute(connection, updateGuard);
        execute(connection, deleteGuard);
        try (var statement = connection.createStatement();
            var rows =
                statement.executeQuery(
                    "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
          while (rows.next()) {
            try (var insert =
                connection.prepareStatement(
                    "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)")) {
              insert.setString(1, rows.getString("name"));
              insert.setString(2, rows.getString("type"));
              insert.setString(
                  3, ModelValues.sha256(rows.getString("sql").getBytes(StandardCharsets.UTF_8)));
              assertEquals(1, insert.executeUpdate());
            }
          }
        }
        execute(connection, "UPDATE format_info SET version=24");
        execute(connection, "PRAGMA user_version=24");
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        assertEquals(
            0,
            scalar(
                connection,
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='base_vector_set_sha256'"));
        execute(connection, "COMMIT");
      } catch (SQLException | RuntimeException | Error failure) {
        execute(connection, "ROLLBACK");
        throw failure;
      } finally {
        execute(connection, "PRAGMA foreign_keys=ON");
      }
    }
  }

  private static long scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static String string(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
