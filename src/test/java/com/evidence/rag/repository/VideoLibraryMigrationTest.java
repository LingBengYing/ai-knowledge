package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoLibraryMigrationTest {
  private static final String NOW = "2026-09-12T00:00:00Z";
  @TempDir Path directory;

  @Test
  void newAuthorityUsesVersionTenAndIndependentVideoForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(24, store.count("PRAGMA user_version"));
            assertEquals(24, store.count("SELECT version FROM format_info"));
            assertEquals(
                6,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('video_compilations','video_frames','video_transcript_spans','video_evidence_groups','video_frame_publication_entries','video_transcript_publication_entries')"));
            assertEquals(
                4,
                store.count(
                    "SELECT COUNT(*) FROM pragma_foreign_key_list('video_evidence_groups') WHERE \"table\" IN ('video_frames','video_transcript_spans')"));
            return null;
          });
    }
  }

  @Test
  void versionNineUpgradeRetainsPublishedTextAndCreatesOneRestorableBackup() throws Exception {
    var actor = new Actor("org", "owner");
    List<PublicationVersion> expected;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(actor, "Retained text before video authority migration.");
      expected =
          fixture
              .evidence
              .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
              .publications();
    }
    restoreVersionNine(directory);
    for (int attempt = 0; attempt < 2; attempt++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(
            expected,
            fixture
                .evidence
                .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
                .publications());
      }
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v9-before-v10-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(
          expected,
          fixture
              .evidence
              .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
              .publications());
    }
  }

  @Test
  void completeVideoRoundTripsOriginalFrameBytesAllSpansGroupsAndEpochAfterReopen() {
    var expected = VideoCompilationFixture.compilation(true);
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            pending(store, repository, "revision");
            assertTrue(repository.findVideoCompilation("revision").isEmpty());
            repository.insertVideoCompilation("revision", expected, NOW);
            repository.markParsed("job-revision", "document-revision", "revision", 0, 0, NOW);
            assertEquals(0, store.count("SELECT COUNT(*) FROM corpus_pages"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM audio_compilations"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM image_evidence"));
            return null;
          });
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            var actual = repository.findVideoCompilation("revision").orElseThrow();
            assertEquals(expected.timelineOriginUs(), actual.timelineOriginUs());
            assertEquals(expected.audio(), actual.audio());
            for (int index = 0; index < expected.frames().size(); index++) {
              assertArrayEquals(
                  expected.frames().get(index).frame().image().content(),
                  actual.frames().get(index).frame().image().content());
            }
            var authority = VideoEvidence.fromCompilation("revision", expected);
            assertEquals(
                authority.manifestSha256(),
                VideoEvidence.fromCompilation("revision", actual).manifestSha256());
            assertEquals(authority.groups(), repository.findVideoGroups("revision"));
            assertArrayEquals(
                VideoCompilationFixture.ORIGINAL, repository.original("document-revision"));
            assertTrue(repository.findVideoCompilation("missing").isEmpty());
            return null;
          });
    }
  }

  @Test
  void videoEvidenceCannotBeChangedReplacedDeletedOrAppendedAfterSeal() throws Exception {
    prepare();
    for (String table :
        List.of(
            "video_compilations",
            "video_frames",
            "video_transcript_spans",
            "video_evidence_groups")) {
      for (String sql :
          List.of(
              "UPDATE " + table + " SET revision_id=revision_id",
              "DELETE FROM " + table,
              "INSERT OR REPLACE INTO " + table + " SELECT * FROM " + table)) {
        assertThrows(SQLException.class, () -> execute(sql));
      }
    }
  }

  @Test
  void videoRevisionCannotBeSealedBeforeCompleteCompilationExists() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            pending(store, repository, "revision");
            return null;
          });
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.markParsed(
                        "job-revision", "document-revision", "revision", 0, 0, NOW);
                    return null;
                  }));
    }
  }

  private void prepare() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            pending(store, repository, "revision");
            repository.insertVideoCompilation(
                "revision", VideoCompilationFixture.compilation(true), NOW);
            repository.markParsed("job-revision", "document-revision", "revision", 0, 0, NOW);
            return null;
          });
    }
  }

  private static void pending(
      SqliteAuthorityStore store, IngestionRepository repository, String revision) {
    String document = "document-" + revision;
    store.execute(
        "INSERT INTO documents VALUES(?,'org','recording.mp4','video','video/mp4',?,?,?,?,'video',NULL)",
        document,
        revision,
        VideoCompilationFixture.SOURCE,
        VideoCompilationFixture.ORIGINAL.length,
        NOW);
    repository.insertOriginal(
        document,
        revision,
        VideoCompilationFixture.COMPILER,
        VideoCompilationFixture.SOURCE,
        VideoCompilationFixture.ORIGINAL,
        NOW);
    repository.insertJob("job-" + revision, document, revision, "owner", NOW);
    repository.markProcessing("job-" + revision, "d".repeat(64), NOW);
  }

  private void execute(String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute(sql);
    }
  }

  /**
   * Only for synthetic @TempDir migration fixtures, never application data or a rollback command.
   */
  static void restoreVersionNine(Path directory) throws SQLException {
    VideoTraceMigrationTest.restoreVersionTen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(10, version.getInt(1));
      }
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM video_compilations")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "Migration fixture must not remove video evidence");
      }
      for (String table :
          List.of("corpus_pages", "corpus_segments", "image_evidence", "audio_compilations")) {
        statement.execute("DROP TRIGGER " + table + "_no_video_identity");
      }
      for (String table :
          List.of(
              "index_publication_entries",
              "image_publication_entries",
              "audio_publication_entries")) {
        statement.execute("DROP TRIGGER " + table + "_no_video_collision");
      }
      statement.execute("DROP TRIGGER corpus_revision_video_complete");
      for (String table :
          List.of(
              "video_frame_publication_entries",
              "video_transcript_publication_entries",
              "video_evidence_groups",
              "video_frames",
              "video_transcript_spans",
              "video_compilations")) {
        statement.execute("DROP TABLE " + table);
      }
      String audioReady =
          """
          AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
            AND r.page_count=0 AND r.segment_count=0
            AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
              AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
              AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
              AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
            AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
            AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
            AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
          """;
      statement.execute("DROP TRIGGER index_publication_identity");
      statement.execute(
          """
          CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications
          WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id
            WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id
              AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id
              AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision
              AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity
              AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions
              AND r.segment_count+(SELECT COUNT(*) FROM image_evidence WHERE revision_id=r.id)
                +(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)=NEW.segment_count
              %s)
          BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
          """
              .formatted(audioReady));
      statement.execute("DROP TRIGGER active_corpus_publication_complete");
      statement.execute(
          """
          CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications
          WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
            AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
            JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
            WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
              AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)
                +(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
              %s)
          BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
          """
              .formatted(audioReady));
      statement.execute("DROP TRIGGER indexing_published_state");
      statement.execute(
          """
          CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs
          WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p
            JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
            JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
            WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id
              AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)
                +(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
              %s)
          BEGIN SELECT RAISE(ABORT,'missing index publication'); END
          """
              .formatted(audioReady));
      statement.execute("UPDATE format_info SET version=9");
      statement.execute("PRAGMA user_version=9");
    }
  }
}
