package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Migration-chain helper only: restores the v10 schema for later migration tests. */
class VideoTraceMigrationTest {

  /** Synthetic @TempDir fixtures only; refuses to discard any video query evidence. */
  static void restoreVersionTen(Path directory) throws SQLException {
    VideoOcrMigrationTest.restoreVersionEleven(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(11, version.getInt(1));
      }
      for (String table :
          List.of("video_trace_evidence", "video_trace_facts", "video_trace_proofs")) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Migration fixture must not remove video query evidence");
        }
      }
      statement.execute("DROP TRIGGER query_traces_complete");
      for (String table :
          List.of("video_trace_evidence", "video_trace_facts", "video_trace_proofs")) {
        statement.execute("DROP TABLE " + table);
      }
      statement.execute(
          """
          CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
            NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
            OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
              +(SELECT COUNT(*) FROM image_trace_evidence WHERE trace_id=NEW.id)
              +(SELECT COUNT(*) FROM audio_trace_evidence WHERE trace_id=NEW.id)
            OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
              OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
            OR (NEW.citation_count>0 AND (SELECT COUNT(DISTINCT citation_ordinal)!=NEW.citation_count
              OR MIN(citation_ordinal)!=1 OR MAX(citation_ordinal)!=NEW.citation_count
              FROM (SELECT citation_ordinal FROM query_trace_evidence WHERE trace_id=NEW.id
                UNION ALL SELECT citation_ordinal FROM image_trace_evidence WHERE trace_id=NEW.id
                UNION ALL SELECT citation_ordinal FROM audio_trace_evidence WHERE trace_id=NEW.id)))
            OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
              JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
          BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
          """);
      statement.execute("UPDATE format_info SET version=10");
      statement.execute("PRAGMA user_version=10");
    }
  }
}
