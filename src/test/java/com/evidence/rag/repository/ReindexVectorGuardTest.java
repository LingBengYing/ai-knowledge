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

/** A removed vector continuation guard refuses the library before queued work is recovered. */
class ReindexVectorGuardTest {
  @TempDir Path directory;

  @Test
  void aRemovedContinuationGuardIsRejectedBeforeAnyQueuedWorkCanBeRecovered() throws Exception {
    String queued;
    try (var authority = new AuthorityTestContext(directory)) {
      var base = ReindexSqlFixture.publish(authority);
      queued = ReindexSqlFixture.queue(authority.store(), base, 1);
    }
    Path database = directory.resolve("java-library.db");
    LibraryFileFixture.execute(database, "DROP TRIGGER image_vector_bindings_identity");
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
