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

/**
 * Physical v24 input, immutable indexed history and a queued legacy task, never a version relabel.
 */
class ReindexVectorMigrationTest {
  @TempDir Path directory;

  @Test
  void realV24RowsOldGuardsSourceAndQueuedLegacySemanticsSurviveABackedUpUpgrade()
      throws Exception {
    String document;
    String queued;
    byte[] original;
    try (var authority = new AuthorityTestContext(directory)) {
      var publication = ReindexSqlFixture.publish(authority);
      document = publication.documentId();
      queued = ReindexSqlFixture.queue(authority.store(), publication, 1);
      original =
          authority
              .store()
              .transaction(() -> new IngestionRepository(authority.store()).original(document));
    }
    ReindexVectorVersion24Fixture.restoreVersionTwentyFour(directory);
    Path database = directory.resolve("java-library.db");
    assertEquals(24, scalar(database, "PRAGMA user_version"));
    var jobs = ReindexVersion23Fixture.rows(database, "SELECT * FROM indexing_jobs ORDER BY id");
    var publications =
        ReindexVersion23Fixture.rows(database, "SELECT * FROM index_publications ORDER BY id");
    var attempts =
        ReindexVersion23Fixture.rows(
            database, "SELECT * FROM indexing_attempts ORDER BY job_id,attempt");
    var active =
        ReindexVersion23Fixture.rows(
            database, "SELECT * FROM active_corpus_publications ORDER BY document_id");
    var objects =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL AND name NOT IN ('indexing_jobs','indexing_identity','indexing_rebuild_identity','active_corpus_publications_no_update') ORDER BY type,name");
    String columns = String.join(",", jobs.getFirst().keySet());
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(jobs, store.rows("SELECT " + columns + " FROM indexing_jobs ORDER BY id"));
            assertEquals(publications, store.rows("SELECT * FROM index_publications ORDER BY id"));
            assertEquals(
                attempts, store.rows("SELECT * FROM indexing_attempts ORDER BY job_id,attempt"));
            assertEquals(
                active,
                store.rows("SELECT * FROM active_corpus_publications ORDER BY document_id"));
            for (Map<String, Object> object : objects) {
              assertEquals(
                  List.of(object),
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE type=? AND name=?",
                      object.get("type"),
                      object.get("name")));
            }
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM indexing_jobs WHERE base_vector_set_sha256 IS NOT NULL"));
            var repository = new IndexingRepository(store);
            assertTrue(repository.sourceCurrent(repository.findInternalTask(queued).orElseThrow()));
            assertTrue(repository.baseVectorSetSha256(queued).isEmpty());
            assertThrows(RuntimeException.class, () -> repository.freezeVectorPlan(queued));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals(1, store.count("PRAGMA foreign_keys"));
            return null;
          });
      assertArrayEquals(
          original, store.transaction(() -> new IngestionRepository(store).original(document)));
      assertTrue(store.managedBackups().known());
      var backups = store.managedBackups().files();
      assertEquals(1, backups.size());
      var backup = backups.getFirst();
      assertTrue(backup.relativePath().startsWith("java-library.v24-before-v25-"));
      Path file = directory.resolve(backup.relativePath());
      assertEquals(24, scalar(file, "PRAGMA user_version"));
      assertEquals(Files.size(file), backup.sizeBytes());
      assertEquals(ModelValues.sha256(Files.readAllBytes(file)), backup.sha256());
      assertEquals(
          jobs, ReindexVersion23Fixture.rows(file, "SELECT * FROM indexing_jobs ORDER BY id"));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      assertEquals(25, reopened.transaction(() -> reopened.count("PRAGMA user_version")));
      assertEquals(1, reopened.managedBackups().files().size());
    }
  }

  @Test
  void aRemovedContinuationGuardIsRejectedBeforeAnyQueuedWorkCanBeRecovered() throws Exception {
    String queued;
    try (var authority = new AuthorityTestContext(directory)) {
      var base = ReindexSqlFixture.publish(authority);
      queued = ReindexSqlFixture.queue(authority.store(), base, 1);
    }
    Path database = directory.resolve("java-library.db");
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER image_vector_bindings_identity");
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

  @Test
  void physicalInverseRefusesARealSealedContinuationRatherThanDroppingItsAudit() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var base = ReindexSqlFixture.publish(authority);
      authority
          .store()
          .transaction(
              () -> {
                var repository = new IndexingRepository(authority.store());
                String id = "vector-snapshot-task";
                var plan =
                    repository.vectorPlanForBase(
                        id, ReindexSqlFixture.OWNER.workspaceId(), base.publicationId());
                repository.insertRebuildJob(
                    id,
                    base.documentId(),
                    repository.parsedRevision(base.documentId()).orElseThrow(),
                    base.claim().target(),
                    ReindexSqlFixture.OWNER.principalId(),
                    base.publicationId(),
                    1,
                    "2026-10-03T00:00:00Z",
                    plan.setSha256());
                return null;
              });
    }
    assertThrows(
        AssertionError.class,
        () -> ReindexVectorVersion24Fixture.restoreVersionTwentyFour(directory));
    assertEquals(25, scalar(directory.resolve("java-library.db"), "PRAGMA user_version"));
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
