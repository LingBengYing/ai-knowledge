package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rebuild guards: indexed tasks are never reset and a changed guard refuses the library. */
class ReindexGuardTest {
  @TempDir Path directory;

  @Test
  void rejectsTheInitialDuplicateAndOrdinarySourceMutation() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      ReindexSqlFixture.requireCurrentVersion(authority.store());
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
      ReindexSqlFixture.requireCurrentVersion(authority.store());
      var publication = ReindexSqlFixture.publish(authority);
      queued = ReindexSqlFixture.queue(authority.store(), publication, 1);
    }
    Path database = directory.resolve("java-library.db");
    LibraryFileFixture.execute(database, "DROP TRIGGER indexing_rebuild_identity");
    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertNull(failure.getCause());
    assertEquals(
        1,
        scalar(
            database,
            "SELECT COUNT(*) FROM indexing_jobs WHERE id='" + queued + "' AND state='queued'"));
    assertEquals(LibrarySchema.VERSION, scalar(database, "PRAGMA user_version"));
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
