package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real v23 history survives a backed-up v24 migration; old indexed tasks are never reset. */
class ReindexMigrationTest {
  @TempDir Path directory;

  @Test
  void realV23PublicationAttemptsSourceAndAllUnchangedSchemaObjectsSurviveTheUpgrade()
      throws Exception {
    ReindexSqlFixture.Published publication;
    List<Map<String, Object>> oldJobs;
    List<Map<String, Object>> oldPublications;
    byte[] original;
    try (var authority = new AuthorityTestContext(directory)) {
      ReindexSqlFixture.requireVersionTwentyFour(authority.store());
      publication = ReindexSqlFixture.publish(authority);
      original =
          authority
              .store()
              .transaction(
                  () ->
                      new IngestionRepository(authority.store())
                          .original(publication.documentId()));
    }
    ReindexVersion23Fixture.restoreVersionTwentyThree(directory);
    Path database = directory.resolve("java-library.db");
    assertEquals(23, scalar(database, "PRAGMA user_version"));
    oldJobs = ReindexVersion23Fixture.rows(database, "SELECT * FROM indexing_jobs ORDER BY id");
    oldPublications =
        ReindexVersion23Fixture.rows(database, "SELECT * FROM index_publications ORDER BY id");
    var oldAttempts =
        ReindexVersion23Fixture.rows(
            database, "SELECT * FROM indexing_attempts ORDER BY job_id,attempt");
    var oldEntries =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT * FROM index_publication_entries ORDER BY publication_id,source_segment_id");
    var oldActive =
        ReindexVersion23Fixture.rows(
            database, "SELECT * FROM active_corpus_publications ORDER BY document_id");
    var oldObjects =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL AND name NOT IN ('indexing_jobs','indexing_identity','active_corpus_publications_no_update') ORDER BY type,name");
    String oldColumns = String.join(",", oldJobs.getFirst().keySet());
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(
                oldJobs, store.rows("SELECT " + oldColumns + " FROM indexing_jobs ORDER BY id"));
            assertEquals(
                oldPublications, store.rows("SELECT * FROM index_publications ORDER BY id"));
            assertEquals(
                oldAttempts, store.rows("SELECT * FROM indexing_attempts ORDER BY job_id,attempt"));
            assertEquals(
                oldEntries,
                store.rows(
                    "SELECT * FROM index_publication_entries ORDER BY publication_id,source_segment_id"));
            assertEquals(
                oldActive,
                store.rows("SELECT * FROM active_corpus_publications ORDER BY document_id"));
            for (var object : oldObjects) {
              assertEquals(
                  List.of(object),
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE type=? AND name=?",
                      object.get("type"),
                      object.get("name")));
            }
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM indexing_jobs WHERE rebuild_sequence=0 AND base_publication_id IS NULL"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals(1, store.count("PRAGMA foreign_keys"));
            return null;
          });
      assertArrayEquals(
          original,
          store.transaction(
              () -> new IngestionRepository(store).original(publication.documentId())));
      var backups = store.managedBackups();
      assertTrue(backups.known());
      assertEquals(2, backups.files().size());
      var backup =
          backups.files().stream()
              .filter(file -> file.relativePath().startsWith("java-library.v23-before-v24-"))
              .findFirst()
              .orElseThrow();
      assertTrue(backup.relativePath().startsWith("java-library.v23-before-v24-"));
      Path file = store.libraryPath().getParent().resolve(backup.relativePath());
      assertEquals(Files.size(file), backup.sizeBytes());
      assertEquals(ModelValues.sha256(Files.readAllBytes(file)), backup.sha256());
      assertEquals(23, scalar(file, "PRAGMA user_version"));
      assertEquals(
          oldJobs, ReindexVersion23Fixture.rows(file, "SELECT * FROM indexing_jobs ORDER BY id"));
      assertEquals(
          oldActive,
          ReindexVersion23Fixture.rows(
              file, "SELECT * FROM active_corpus_publications ORDER BY document_id"));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      ReindexSqlFixture.requireVersionTwentyFour(reopened);
      assertEquals(2, reopened.managedBackups().files().size());
    }
  }

  @Test
  void v24StillRejectsTheInitialDuplicateAndOrdinarySourceMutation() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      ReindexSqlFixture.requireVersionTwentyFour(authority.store());
      var publication = ReindexSqlFixture.publish(authority);
      assertThrows(
          RuntimeException.class,
          () ->
              authority.createIndexing(
                  ReindexSqlFixture.OWNER, publication.documentId(), publication.claim().target()));
      assertThrows(
          RuntimeException.class,
          () ->
              authority
                  .store()
                  .transaction(
                      () -> {
                        authority
                            .store()
                            .execute(
                                "UPDATE indexing_jobs SET state='queued',attempt=1,projection_generation_id=NULL WHERE id=?",
                                publication.claim().jobId());
                        return null;
                      }));
      assertThrows(
          RuntimeException.class,
          () ->
              authority
                  .store()
                  .transaction(
                      () -> {
                        authority
                            .store()
                            .execute("UPDATE corpus_revisions SET parser_revision='replacement'");
                        return null;
                      }));
      assertEquals(
          publication.publicationId(),
          ReindexSqlFixture.active(authority.store(), publication.documentId()));
    }
  }

  @Test
  void changedRebuildGuardIsRejectedBeforeAnyPendingClaimRecovery() throws Exception {
    String queued;
    try (var authority = new AuthorityTestContext(directory)) {
      ReindexSqlFixture.requireVersionTwentyFour(authority.store());
      var publication = ReindexSqlFixture.publish(authority);
      queued = ReindexSqlFixture.queue(authority.store(), publication, 1);
    }
    Path database = directory.resolve("java-library.db");
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER indexing_rebuild_identity");
    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertNull(failure.getCause());
    assertEquals(
        1,
        scalar(
            database,
            "SELECT COUNT(*) FROM indexing_jobs WHERE id='" + queued + "' AND state='queued'"));
    assertEquals(25, scalar(database, "PRAGMA user_version"));
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
