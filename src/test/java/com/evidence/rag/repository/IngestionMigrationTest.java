package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Historical SQLite v1 fixtures exercise upgrade and recovery through the Module's Interface. */
class IngestionMigrationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private final Actor reader = new Actor("org", "reader");

  @Test
  void v1UpgradePreservesSyntheticAclMetadataAndKeepsRestorablePreUpgradeBackup() throws Exception {
    Path original = directory.resolve("original");
    versionOne(original);
    try (var authority = new AuthorityTestContext(original)) {
      assertEquals(24, scalar(original, "PRAGMA user_version"));
      var before = first(authority.listDocuments(reader, Map.of()));
      assertEquals("existing", before.get("document_id"));
      assertEquals("original.pdf", before.get("filename"));
      assertEquals("registration-revision", before.get("registered_revision_id"));
      assertNull(before.get("active_revision_id"));
      assertNull(before.get("index_publication_id"));
      assertEquals("display", before.get("display_name"));
      assertEquals("folder", before.get("folder_id"));
      assertEquals(List.of("existing-tag"), before.get("tags"));
      assertEquals(true, before.get("synthetic_fixture"));
      assertEquals(false, before.get("can_edit"));
      assertEquals(
          1L,
          authority.listFolders(reader).get("items") instanceof List<?> list
              ? (long) list.size()
              : -1L);
      assertEquals(1, ((List<?>) authority.auditEvents(owner).get("items")).size());
      var added = authority.uploadDocument(owner, "new.txt", "text/plain", new byte[] {65});
      assertEquals("queued", added.get("state"));
      assertEquals(1L, authority.listDocuments(reader, Map.of()).get("total"));
      assertThrows(
          SQLException.class,
          () ->
              sql(
                  original,
                  "UPDATE documents SET active_revision_id='replaced' WHERE id='existing'"));
    }
    List<Path> backups;
    try (var paths = Files.list(original)) {
      backups =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v1-before-v2-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    assertEquals(1, scalar(restored, "PRAGMA user_version"));
    assertEquals(1, scalar(restored, "SELECT COUNT(*) FROM documents"));
    assertEquals(
        0, scalar(restored, "SELECT COUNT(*) FROM sqlite_master WHERE name='corpus_documents'"));
    try (var restoredAuthority = new AuthorityTestContext(restored)) {
      assertEquals(
          "original.pdf", first(restoredAuthority.listDocuments(reader, Map.of())).get("filename"));
      assertEquals(1L, restoredAuthority.listDocuments(owner, Map.of()).get("total"));
    }
    try (var reopened = new AuthorityTestContext(original)) {
      assertEquals(2L, reopened.listDocuments(owner, Map.of()).get("total"));
    }
    try (var paths = Files.list(original)) {
      assertEquals(
          1L,
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v1-before-v2-")
                          && path.toString().endsWith(".db"))
              .count());
    }
  }

  @Test
  void migrationFailureRollsBackAllDdlAndLeavesVersionOneAndCompletedBackup() throws Exception {
    versionOne(directory);
    sql(directory, "CREATE TABLE corpus_documents(unexpected TEXT)");
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    assertEquals(1, scalar(directory, "PRAGMA user_version"));
    assertEquals(1, scalar(directory, "SELECT version FROM format_info"));
    assertEquals(1, scalar(directory, "SELECT COUNT(*) FROM documents"));
    assertEquals(
        0, scalar(directory, "SELECT COUNT(*) FROM sqlite_master WHERE name='corpus_revisions'"));
    assertEquals(
        0, scalar(directory, "SELECT COUNT(*) FROM sqlite_master WHERE name='ingestion_jobs'"));
    try (var paths = Files.list(directory)) {
      assertEquals(
          1L,
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v1-before-v2-")
                          && path.toString().endsWith(".db"))
              .count());
    }
  }

  @Test
  void unknownOrInconsistentVersionIsRefusedBeforeBackupOrMigration() throws Exception {
    versionOne(directory);
    sql(directory, "PRAGMA user_version=3");
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    assertEquals(3, scalar(directory, "PRAGMA user_version"));
    sql(directory, "PRAGMA user_version=2");
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    assertEquals(1, scalar(directory, "SELECT version FROM format_info"));
    assertEquals(
        0, scalar(directory, "SELECT COUNT(*) FROM sqlite_master WHERE name='corpus_documents'"));
    try (var paths = Files.list(directory)) {
      assertEquals(
          0L,
          paths
              .filter(
                  path -> path.getFileName().toString().startsWith("java-library.v1-before-v2-"))
              .count());
    }
  }

  private static void versionOne(Path directory) throws Exception {
    Files.createDirectories(directory);
    for (String statement :
        List.of(
            "PRAGMA application_id=1163280711",
            "PRAGMA user_version=1",
            "CREATE TABLE format_info(format TEXT PRIMARY KEY,version INTEGER NOT NULL)",
            "INSERT INTO format_info VALUES('evidence-rag-java-management-v1',1)",
            "CREATE TABLE folders(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,owner_id TEXT NOT NULL,name TEXT NOT NULL,name_key TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(workspace_id,owner_id,name_key))",
            "CREATE TABLE documents(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,filename TEXT NOT NULL,document_type TEXT NOT NULL,mime_type TEXT NOT NULL,active_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,size_bytes INTEGER NOT NULL,updated_at TEXT NOT NULL,display_name TEXT NOT NULL,folder_id TEXT REFERENCES folders(id) ON DELETE RESTRICT)",
            "CREATE TABLE document_acl(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,principal_id TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('owner','editor','reader')),PRIMARY KEY(document_id,principal_id))",
            "CREATE TABLE document_tags(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,tag TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(document_id,tag))",
            "CREATE TABLE management_audit(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,entity_id TEXT NOT NULL,action TEXT NOT NULL,fields_json TEXT NOT NULL,before_sha256 TEXT NOT NULL,after_sha256 TEXT NOT NULL,created_at TEXT NOT NULL)",
            "CREATE TRIGGER audit_no_update BEFORE UPDATE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END",
            "CREATE TRIGGER audit_no_delete BEFORE DELETE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END",
            "CREATE TRIGGER immutable_identity BEFORE UPDATE OF filename,workspace_id,document_type,mime_type,active_revision_id,source_sha256,size_bytes ON documents BEGIN SELECT RAISE(ABORT,'immutable source identity'); END",
            "INSERT INTO folders VALUES('folder','org','owner','shared','shared','2026-01-01')",
            "INSERT INTO documents VALUES('existing','org','original.pdf','document','application/pdf','registration-revision','"
                + "a".repeat(64)
                + "',123,'2026-01-01','display','folder')",
            "INSERT INTO document_acl VALUES('existing','owner','owner')",
            "INSERT INTO document_acl VALUES('existing','reader','reader')",
            "INSERT INTO document_tags VALUES('existing','existing-tag',0)",
            "INSERT INTO management_audit VALUES('audit','org','owner','existing','document_updated','[\"display_name\"]','"
                + "b".repeat(64)
                + "','"
                + "c".repeat(64)
                + "','2026-01-01')")) {
      sql(directory, statement);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> first(Map<String, Object> response) {
    return ((List<Map<String, Object>>) response.get("items")).getFirst();
  }

  private static void sql(Path directory, String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static int scalar(Path directory, String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }
}
