package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoLibrarySchemaTest {
  private static final String NOW = "2026-09-12T00:00:00Z";
  @TempDir Path directory;

  @Test
  void newAuthorityHasIndependentVideoForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
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
            // findVideoCompilation verifies the stored groups against the recomputed authority.
            assertEquals(
                authority.groups().size(),
                store.count(
                    "SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=?", "revision"));
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
}
