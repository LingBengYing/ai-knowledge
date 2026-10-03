package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualLibraryMigrationTest {
  @TempDir Path directory;

  @Test
  void newAuthorityUsesVersionSevenWithSeparateImageEvidenceAndCitationTables() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // The public Store performs the supported migration chain.
    }
    try (var database =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = database.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(24, version.getInt(1));
      }
      try (var tables =
          statement.executeQuery(
              "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('image_evidence','image_publication_entries','image_trace_evidence')")) {
        assertTrue(tables.next());
        assertEquals(3, tables.getInt(1));
      }
    }
  }

  /** Restore exact historical triggers before a test exercises a v1-v6 fixture. */
  static void restoreVersionSix(Path directory) throws java.sql.SQLException {
    AudioLibraryMigrationTest.restoreVersionSeven(directory);
    try (var database =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = database.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute("DROP TRIGGER corpus_revision_visual_complete");
      statement.execute("DROP TRIGGER index_publication_entries_no_image_collision");
      statement.execute("DROP TABLE image_trace_evidence");
      statement.execute("DROP TABLE image_publication_entries");
      statement.execute("DROP TABLE image_evidence");
      statement.execute("DROP TRIGGER index_publication_identity");
      statement.execute(
          """
          CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions AND r.segment_count=NEW.segment_count) BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
          """);
      statement.execute("DROP TRIGGER active_corpus_publication_complete");
      statement.execute(
          """
          CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries e WHERE e.publication_id=p.id)) BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
          """);
      statement.execute("DROP TRIGGER indexing_published_state");
      statement.execute(
          """
          CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries e WHERE e.publication_id=p.id)) BEGIN SELECT RAISE(ABORT,'missing index publication'); END
          """);
      statement.execute("DROP TRIGGER query_traces_complete");
      statement.execute(
          """
          CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
          NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
          OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
          OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
          OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
          OR (NEW.citation_count>0 AND ((SELECT MIN(citation_ordinal) FROM query_trace_evidence WHERE trace_id=NEW.id)!=1
          OR (SELECT MAX(citation_ordinal) FROM query_trace_evidence WHERE trace_id=NEW.id)!=NEW.citation_count))
          OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
          JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
          BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
          """);
      statement.execute("UPDATE format_info SET version=6");
      statement.execute("PRAGMA user_version=6");
    }
  }
}
