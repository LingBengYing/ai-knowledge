package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoTraceMigrationTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void versionTenUpgradeRetainsVideoTextAndOldTraceWithOneRestorableBackup() throws Exception {
    EvidenceScope expected;
    List<String> videoIds;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(OWNER, "Retained before v11 trace migration.");
      videoIds = VideoTraceRepositoryTest.publishVideo(fixture);
      expected =
          fixture.evidence.snapshot(
              OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET);
      var repository = new EvidenceRepository(fixture.authority.store());
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                repository.insertTrace(
                    "old-refusal",
                    expected,
                    new TraceDraft(
                        "a".repeat(64),
                        null,
                        "abstained",
                        "insufficient_evidence",
                        "model-v1",
                        "prompt-v1",
                        "policy-v1",
                        List.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    "2026-09-20T08:00:00Z");
                return null;
              });
    }
    restoreVersionTen(directory);
    assertEquals(10, scalar(directory.resolve("java-library.db"), "PRAGMA user_version"));
    for (int reopening = 0; reopening < 2; reopening++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(
            expected,
            fixture.evidence.snapshot(
                OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET));
        fixture
            .authority
            .store()
            .transaction(
                () -> {
                  var repository = new EvidenceRepository(fixture.authority.store());
                  assertEquals(
                      4, repository.findPublishedVideoCandidates(expected, videoIds).size());
                  assertEquals(
                      1,
                      fixture
                          .authority
                          .store()
                          .count(
                              "SELECT COUNT(*) FROM query_traces WHERE id='old-refusal' AND question_sha256=? AND outcome='abstained'",
                              "a".repeat(64)));
                  assertEquals(25, fixture.authority.store().count("PRAGMA user_version"));
                  return null;
                });
      }
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  p ->
                      p.getFileName().toString().startsWith("java-library.v10-before-v11-")
                          && p.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    assertEquals(10, scalar(backups.getFirst(), "PRAGMA user_version"));
    assertEquals(1, scalar(backups.getFirst(), "SELECT COUNT(*) FROM video_compilations"));
    assertEquals(
        0,
        scalar(
            backups.getFirst(),
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='video_trace_proofs'"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(
          expected,
          fixture.evidence.snapshot(
              OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET));
    }
  }

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

  private static long scalar(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }
}
