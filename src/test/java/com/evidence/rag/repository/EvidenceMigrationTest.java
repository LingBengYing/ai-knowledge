package com.evidence.rag.repository;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceMigrationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void v3UpgradeBacksUpRealPublicationAndPreservesSourceAndActiveConstraints() throws Exception {
    versionThree();
    try (var fixture = new PublishedCorpusFixture(directory)) {
      assertEquals(25, scalar("PRAGMA user_version"));
      assertEquals(25, scalar("SELECT version FROM format_info"));
      assertEquals(
          1,
          fixture
              .evidence
              .snapshot(owner, DocumentSelection.allDocuments(), TARGET)
              .publications()
              .size());
      assertEquals(1, scalar("SELECT COUNT(*) FROM index_publication_entries"));
      assertEquals(1, scalar("SELECT COUNT(*) FROM indexing_attempts"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_traces"));
      for (String statement :
          List.of(
              "DELETE FROM corpus_pages",
              "UPDATE corpus_segments SET text='changed'",
              "DELETE FROM index_publications",
              "UPDATE active_corpus_publications SET revision_id='changed'")) {
        assertThrows(SQLException.class, () -> sql(statement));
      }
    }
    assertEquals(1, backups().size());
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + backups().getFirst());
        var statement = db.createStatement();
        var result = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(result.next());
      assertEquals(3, result.getInt(1));
    }
    try (var reopened = new PublishedCorpusFixture(directory)) {
      assertEquals(
          1,
          reopened
              .evidence
              .snapshot(owner, DocumentSelection.allDocuments(), TARGET)
              .publications()
              .size());
    }
    assertEquals(1, backups().size());
  }

  @Test
  void v4DdlFailureRollsBackAndKeepsConsistentV3Backup() throws Exception {
    versionThree();
    sql("CREATE TABLE query_traces(unexpected TEXT)");
    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertNull(failure.getCause());
    assertEquals(3, scalar("PRAGMA user_version"));
    assertEquals(3, scalar("SELECT version FROM format_info"));
    assertEquals(1, scalar("SELECT COUNT(*) FROM active_corpus_publications"));
    assertEquals(
        0, scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='query_trace_documents'"));
    assertEquals(1, backups().size());
  }

  @Test
  void failedV4BackupRefusesMigrationWithoutCompletedOutput() throws Exception {
    versionThree();
    var permissions = Files.getPosixFilePermissions(directory);
    try {
      Files.setPosixFilePermissions(
          directory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
      assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    } finally {
      Files.setPosixFilePermissions(directory, permissions);
    }
    assertEquals(3, scalar("PRAGMA user_version"));
    assertEquals(1, scalar("SELECT COUNT(*) FROM active_corpus_publications"));
    assertTrue(backups().isEmpty());
  }

  @Test
  void partialV4AndMissingInheritedGenerationLedgerAreRejectedBeforeUse() throws Exception {
    versionFour();
    assertEquals(4, scalar("PRAGMA user_version"));
    sql("DROP TABLE query_trace_evidence");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertEquals(4, scalar("PRAGMA user_version"));
  }

  @Test
  void v4StillRejectsCorruptV3GenerationShape() throws Exception {
    versionFour();
    assertEquals(4, scalar("PRAGMA user_version"));
    sql("DROP TABLE indexing_attempts");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertEquals(4, scalar("PRAGMA user_version"));
  }

  private void versionThree() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(owner, "Published fixture.");
    }
    stripVersionFive();
    for (String table : List.of("query_trace_evidence", "query_trace_documents", "query_traces")) {
      sql("DROP TABLE IF EXISTS " + table);
    }
    sql("UPDATE format_info SET version=3");
    sql("PRAGMA user_version=3");
    assertEquals(3, scalar("PRAGMA user_version"));
  }

  private void versionFour() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Produce the current format through its public lifecycle before restoring the v4 fixture.
    }
    stripVersionFive();
  }

  private void stripVersionFive() throws SQLException {
    VisualLibraryMigrationTest.restoreVersionSix(directory);
    sql("DROP TABLE IF EXISTS image_text_regions");
    sql("DROP TABLE IF EXISTS document_tombstones");
    sql("UPDATE format_info SET version=4");
    sql("PRAGMA user_version=4");
  }

  private List<Path> backups() throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(
              path ->
                  path.getFileName().toString().startsWith("java-library.v3-before-v4-")
                      && path.toString().endsWith(".db"))
          .toList();
    }
  }

  private void sql(String command) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement()) {
      statement.execute(command);
    }
  }

  private int scalar(String command) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement();
        var result = statement.executeQuery(command)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }
}
