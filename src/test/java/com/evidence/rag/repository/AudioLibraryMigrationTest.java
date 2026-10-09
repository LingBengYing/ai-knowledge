package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioLibraryMigrationTest {
  private static final byte[] ORIGINAL = "synthetic audio source".getBytes(StandardCharsets.UTF_8);
  private static final String SOURCE_SHA = ModelValues.sha256(ORIGINAL);
  private static final String COMPILER = "java-audio-compiler-v1:" + "c".repeat(64);
  private static final String NOW = "2026-09-10T08:00:00Z";
  @TempDir Path directory;

  @Test
  void newAuthorityUsesVersionEightAndIndependentAudioPreparationAndPublication() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // The public Store must apply its full, versioned migration chain.
    }
    assertEquals(
        HistoricalSchemaV25Fixture.CURRENT_VERSION, scalar(database(), "PRAGMA user_version"));
    assertEquals(
        HistoricalSchemaV25Fixture.CURRENT_VERSION,
        scalar(database(), "SELECT version FROM format_info"));
    assertEquals(
        3,
        scalar(
            database(),
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('audio_compilations','audio_spans','audio_publication_entries')"));
    assertEquals(
        1,
        scalar(
            database(),
            "SELECT COUNT(*) FROM pragma_foreign_key_list('audio_compilations') WHERE \"table\"='corpus_revisions'"));
    assertEquals(
        1,
        scalar(
            database(),
            "SELECT COUNT(*) FROM pragma_foreign_key_list('audio_spans') WHERE \"table\"='audio_compilations'"));
    assertEquals(
        2,
        scalar(
            database(),
            "SELECT COUNT(*) FROM pragma_foreign_key_list('audio_publication_entries') WHERE \"table\" IN ('index_publications','audio_spans')"));
    // v9 now has a real answer consumer; v8's independent preparation tables remain unchanged.
    assertEquals(
        1,
        scalar(
            database(),
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='audio_trace_evidence'"));
  }

  @Test
  void versionSevenUpgradeKeepsPublishedTextAndCreatesOneRestorableBackup() throws Exception {
    var actor = new Actor("org", "owner");
    List<PublicationVersion> before;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(actor, "Retained text evidence from version seven.");
      before =
          fixture
              .evidence
              .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
              .publications();
    }
    restoreVersionSeven(directory);
    assertEquals(7, scalar(database(), "PRAGMA user_version"));
    for (int attempt = 0; attempt < 2; attempt++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(
            before,
            fixture
                .evidence
                .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
                .publications());
      }
      assertEquals(
          HistoricalSchemaV25Fixture.CURRENT_VERSION, scalar(database(), "PRAGMA user_version"));
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v7-before-v8-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    assertEquals(7, scalar(backups.getFirst(), "PRAGMA user_version"));
    assertEquals(1, scalar(backups.getFirst(), "SELECT COUNT(*) FROM active_corpus_publications"));
    assertEquals(
        0,
        scalar(
            backups.getFirst(),
            "SELECT COUNT(*) FROM sqlite_master WHERE name='audio_compilations'"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(
          before,
          fixture
              .evidence
              .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
              .publications());
    }
  }

  @Test
  void completePreparationRoundTripsEverySilentSpanAndSeparatesContiguousIndexOrdinals() {
    var expected = compilation();
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            pendingAudio(store, repository, "revision");
            assertTrue(repository.findAudioCompilation("revision").isEmpty());
            repository.insertAudioCompilation("revision", expected, NOW);
            repository.markParsed("job-revision", "document-revision", "revision", 0, 0, NOW);
            assertEquals(expected, repository.findAudioCompilation("revision").orElseThrow());
            assertTrue(repository.findAudioCompilation("missing").isEmpty());
            assertEquals(0, store.count("SELECT COUNT(*) FROM corpus_pages"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM corpus_segments"));
            assertEquals(
                0,
                store.count(
                    "SELECT page_count+segment_count FROM corpus_revisions WHERE id='revision'"));
            assertEquals(
                5,
                store.count(
                    "SELECT span_count FROM audio_compilations WHERE revision_id='revision'"));
            assertEquals(
                2,
                store.count(
                    "SELECT projection_count FROM audio_compilations WHERE revision_id='revision'"));
            var rows =
                store.rows(
                    "SELECT * FROM audio_spans WHERE revision_id='revision' ORDER BY ordinal");
            assertEquals(5, rows.size());
            assertNull(rows.get(0).get("index_ordinal"));
            assertEquals(0, AuthorityRows.integer(rows.get(1), "index_ordinal"));
            assertNull(rows.get(2).get("index_ordinal"));
            assertEquals(1, AuthorityRows.integer(rows.get(3), "index_ordinal"));
            assertNull(rows.get(4).get("index_ordinal"));
            assertEquals("\u2003", AuthorityRows.text(rows.get(2), "text"));
            assertEquals(
                ModelValues.sha256(
                    "\n蓝港计划的预算是42万元。😀\n\u2003\n负责人是林溪。\n\t".getBytes(StandardCharsets.UTF_8)),
                AuthorityRows.text(
                    store
                        .rows(
                            "SELECT transcript_sha256 FROM audio_compilations WHERE revision_id='revision'")
                        .getFirst(),
                    "transcript_sha256"));
            return null;
          });
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(reopened);
      assertEquals(
          expected,
          reopened.transaction(() -> repository.findAudioCompilation("revision").orElseThrow()));
    }
  }

  @Test
  void audioEvidenceIdentityIsStableAndSilentSpansNeverReceiveProjectionOrdinals() {
    var evidence = AudioEvidence.fromCompilation("revision", compilation());
    assertEquals(5, evidence.size());
    assertEquals(evidence, AudioEvidence.fromCompilation("revision", compilation()));
    assertEquals(
        "audio-" + ModelValues.sha256("revision\0".concat("1").getBytes(StandardCharsets.UTF_8)),
        evidence.get(1).id());
    assertNull(evidence.getFirst().indexOrdinal());
    assertEquals(0, evidence.get(1).indexOrdinal());
    assertNull(evidence.get(2).indexOrdinal());
    assertEquals(1, evidence.get(3).indexOrdinal());
    assertNull(evidence.getLast().indexOrdinal());
    assertEquals(5001, evidence.getLast().endMs());
    assertEquals(compilation().spans().get(1).textSha256(), evidence.get(1).textSha256());
    assertEquals("AudioEvidence[redacted]", evidence.get(1).toString());
    assertThrows(UnsupportedOperationException.class, evidence::clear);
  }

  @Test
  void storedAudioCannotBeReplacedChangedOrDeletedAfterItsIdentityIsFrozen() throws Exception {
    prepare();
    for (String statement :
        List.of(
            "UPDATE audio_compilations SET decoder_revision='changed'",
            "DELETE FROM audio_compilations",
            "INSERT OR REPLACE INTO audio_compilations SELECT * FROM audio_compilations",
            "UPDATE audio_spans SET text='changed'",
            "DELETE FROM audio_spans",
            "INSERT OR REPLACE INTO audio_spans SELECT * FROM audio_spans")) {
      assertThrows(SQLException.class, () -> sql(directory, statement));
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      assertEquals(
          compilation(),
          store.transaction(() -> repository.findAudioCompilation("revision").orElseThrow()));
    }
  }

  @Test
  void aForeignSourceHashOrUnfinishedTimelineCannotBecomeAParsedRevision() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            pendingAudio(store, repository, "revision");
            return null;
          });
      var foreign =
          new AudioCompilation(
              "a".repeat(64),
              "decoder-v1",
              "asr-v1",
              COMPILER,
              compilation().durationMs(),
              compilation().spans());
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.insertAudioCompilation("revision", foreign, NOW);
                    return null;
                  }));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.markParsed(
                        "job-revision", "document-revision", "revision", 0, 0, NOW);
                    return null;
                  }));
      store.transaction(
          () -> {
            assertTrue(repository.findAudioCompilation("revision").isEmpty());
            assertEquals(
                0,
                store.count("SELECT COUNT(*) FROM corpus_revisions WHERE parsed_at IS NOT NULL"));
            repository.insertAudioCompilation("revision", compilation(), NOW);
            repository.markParsed("job-revision", "document-revision", "revision", 0, 0, NOW);
            return null;
          });
    }
  }

  private void prepare() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      store.transaction(
          () -> {
            pendingAudio(store, repository, "revision");
            repository.insertAudioCompilation("revision", compilation(), NOW);
            repository.markParsed("job-revision", "document-revision", "revision", 0, 0, NOW);
            return null;
          });
    }
  }

  private static AudioCompilation compilation() {
    return new AudioCompilation(
        SOURCE_SHA,
        "decoder-v1",
        "asr-v1",
        COMPILER,
        5001,
        List.of(
            new AudioTranscriptSpan(0, 0, 1000, ""),
            new AudioTranscriptSpan(1, 1000, 2000, "蓝港计划的预算是42万元。😀"),
            new AudioTranscriptSpan(2, 2000, 3000, "\u2003"),
            new AudioTranscriptSpan(3, 3000, 5000, "负责人是林溪。"),
            new AudioTranscriptSpan(4, 5000, 5001, "\t")));
  }

  private static void pendingAudio(
      SqliteAuthorityStore store, IngestionRepository repository, String revision) {
    String document = "document-" + revision;
    store.execute(
        "INSERT INTO documents VALUES(?, 'org','recording.wav','audio','audio/wav',?,?,?,?,'recording',NULL)",
        document,
        revision,
        SOURCE_SHA,
        ORIGINAL.length,
        NOW);
    repository.insertOriginal(document, revision, COMPILER, SOURCE_SHA, ORIGINAL, NOW);
    repository.insertJob("job-" + revision, document, revision, "owner", NOW);
    repository.markProcessing("job-" + revision, "d".repeat(64), NOW);
  }

  /** Restore only this slice so inherited v1-v7 fixtures exercise their original migration. */
  static void restoreVersionSeven(Path directory) throws SQLException {
    AudioTraceMigrationTest.restoreVersionEight(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute("DROP TRIGGER corpus_revision_audio_complete");
      statement.execute("DROP TRIGGER index_publication_entries_no_audio_collision");
      statement.execute("DROP TRIGGER image_publication_entries_no_audio_collision");
      statement.execute("DROP TABLE audio_publication_entries");
      statement.execute("DROP TABLE audio_spans");
      statement.execute("DROP TABLE audio_compilations");
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
              AND r.segment_count+(SELECT COUNT(*) FROM image_evidence WHERE revision_id=r.id)=NEW.segment_count)
          BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
          """);
      statement.execute("DROP TRIGGER active_corpus_publication_complete");
      statement.execute(
          """
          CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications
          WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
            AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
            WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
              AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id))
          BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
          """);
      statement.execute("DROP TRIGGER indexing_published_state");
      statement.execute(
          """
          CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs
          WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p
            JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
            WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id
              AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id))
          BEGIN SELECT RAISE(ABORT,'missing index publication'); END
          """);
      statement.execute("UPDATE format_info SET version=7");
      statement.execute("PRAGMA user_version=7");
    }
  }

  private Path database() {
    return directory.resolve("java-library.db");
  }

  private static long scalar(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static void sql(Path directory, String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute(sql);
    }
  }
}
