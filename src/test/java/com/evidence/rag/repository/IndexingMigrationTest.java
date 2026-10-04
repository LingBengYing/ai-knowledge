package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingMigrationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void v2UpgradeBacksUpConsistentParsedEvidenceAndPreservesAllOldConstraints() throws Exception {
    String document = versionTwo(directory);
    try (var authority = new AuthorityTestContext(directory)) {
      assertEquals(25, scalar(directory, "PRAGMA user_version"));
      assertEquals(25, scalar(directory, "SELECT version FROM format_info"));
      assertEquals(1, authority.parsedEvidence(owner, document).segments().size());
      assertEquals(0, scalar(directory, "SELECT COUNT(*) FROM active_corpus_publications"));
      assertEquals(
          0,
          scalar(
              directory,
              "SELECT COUNT(*) FROM corpus_documents WHERE active_revision_id IS NOT NULL"));
      for (String statement :
          List.of(
              "UPDATE corpus_documents SET active_revision_id='fake'",
              "UPDATE corpus_documents SET original_blob=X'01'",
              "UPDATE corpus_revisions SET parser_revision='fake'",
              "UPDATE corpus_segments SET text='fake'",
              "DELETE FROM corpus_pages")) {
        assertThrows(SQLException.class, () -> sql(directory, statement));
      }
      authority.uploadDocument(owner, "post-upgrade.txt", "text/plain", new byte[] {65});
    }
    var backups = backups(directory);
    assertEquals(1, backups.size());
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    assertEquals(2, scalar(restored, "PRAGMA user_version"));
    assertEquals(1, scalar(restored, "SELECT COUNT(*) FROM documents"));
    assertEquals(1, scalar(restored, "SELECT COUNT(*) FROM corpus_segments"));
    assertEquals(
        0, scalar(restored, "SELECT COUNT(*) FROM sqlite_master WHERE name='indexing_jobs'"));
    try (var authority = new AuthorityTestContext(directory)) {
      assertEquals(2L, authority.listDocuments(owner, Map.of()).get("total"));
    }
    assertEquals(1, backups(directory).size());
  }

  @Test
  void v3DdlFailureRollsBackAndKeepsCompletedV2Backup() throws Exception {
    versionTwo(directory);
    sql(directory, "CREATE TABLE index_publications(unexpected TEXT)");
    var failure =
        assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    assertNull(failure.getCause(), "Migration failure must not expose an internal SQL/path cause");
    assertEquals(2, scalar(directory, "PRAGMA user_version"));
    assertEquals(2, scalar(directory, "SELECT version FROM format_info"));
    assertEquals(1, scalar(directory, "SELECT COUNT(*) FROM corpus_segments"));
    assertEquals(
        0, scalar(directory, "SELECT COUNT(*) FROM sqlite_master WHERE name='indexing_jobs'"));
    assertEquals(1, backups(directory).size());
  }

  @Test
  void failedBackupRefusesMigrationAndNeverLabelsPartialOutputAsComplete() throws Exception {
    versionTwo(directory);
    var permissions = Files.getPosixFilePermissions(directory);
    try {
      Files.setPosixFilePermissions(
          directory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
      assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    } finally {
      Files.setPosixFilePermissions(directory, permissions);
    }
    assertEquals(2, scalar(directory, "PRAGMA user_version"));
    assertEquals(2, scalar(directory, "SELECT version FROM format_info"));
    assertEquals(
        0, scalar(directory, "SELECT COUNT(*) FROM sqlite_master WHERE name='indexing_jobs'"));
    assertTrue(backups(directory).isEmpty());
    try (var authority = new AuthorityTestContext(directory)) {
      assertEquals(1L, authority.listDocuments(owner, Map.of()).get("total"));
    }
  }

  @Test
  void preGenerationWorkInProgressV3IsRefusedWithoutMigrationOrRecovery() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      authority.uploadDocument(owner, "fixture.txt", "text/plain", new byte[] {65});
    }
    stripVersionFour(directory);
    sql(directory, "DROP TABLE IF EXISTS indexing_attempts");
    var failure =
        assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    assertNull(failure.getCause());
    assertEquals(3, scalar(directory, "PRAGMA user_version"));
    assertEquals(1, scalar(directory, "SELECT COUNT(*) FROM ingestion_jobs WHERE state='queued'"));
    assertTrue(backups(directory).isEmpty());
  }

  /** Remove only the new sidecar, retaining the unchanged historical v2 schema and evidence. */
  private String versionTwo(Path path) throws Exception {
    String document;
    try (var authority = new AuthorityTestContext(path)) {
      authority.uploadDocument(
          owner, "fixture.txt", "text/plain", "fixture".getBytes(StandardCharsets.UTF_8));
      var claim = authority.claimIngestion("org").orElseThrow();
      document = claim.documentId();
      assertTrue(
          authority.completeIngestion(
              claim, new TextParser().parse("fixture.txt", "text/plain", claim.content())));
    }
    stripVersionFour(path);
    for (String table :
        List.of(
            "active_corpus_publications",
            "index_publication_entries",
            "index_publications",
            "indexing_attempts",
            "indexing_jobs")) {
      sql(path, "DROP TABLE IF EXISTS " + table);
    }
    sql(path, "UPDATE format_info SET version=2");
    sql(path, "PRAGMA user_version=2");
    return document;
  }

  /** Restore the exact v3 fixture before testing historical v2/v3 migration behavior. */
  private static void stripVersionFour(Path path) throws SQLException {
    VisualLibraryMigrationTest.restoreVersionSix(path);
    sql(path, "DROP TABLE IF EXISTS image_text_regions");
    sql(path, "DROP TABLE IF EXISTS document_tombstones");
    for (String table : List.of("query_trace_evidence", "query_trace_documents", "query_traces")) {
      sql(path, "DROP TABLE IF EXISTS " + table);
    }
    sql(path, "UPDATE format_info SET version=3");
    sql(path, "PRAGMA user_version=3");
  }

  private static List<Path> backups(Path path) throws Exception {
    try (var files = Files.list(path)) {
      return files
          .filter(
              file ->
                  file.getFileName().toString().startsWith("java-library.v2-before-v3-")
                      && file.toString().endsWith(".db"))
          .toList();
    }
  }

  private static void sql(Path directory, String sql) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement()) {
      statement.execute(sql);
    }
  }

  private static int scalar(Path directory, String sql) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }
}
