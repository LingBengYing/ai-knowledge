package com.evidence.rag.repository;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real persisted format checks through the public authority-store lifecycle. */
class DocumentLifecycleMigrationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private final DocumentRemovalEntity removal =
      new DocumentRemovalEntity("removal-document", "org", "owner", "2026-09-08T00:00:00Z");

  @Test
  void newAuthorityUsesVersionFiveLifecycleFormat() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Opening a new authority must complete its entire supported migration chain.
    }
    try (var database =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = database.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(5, version.getInt(1));
      }
      try (var format = statement.executeQuery("SELECT version FROM format_info")) {
        assertTrue(format.next());
        assertEquals(5, format.getInt(1));
      }
    }
  }

  @Test
  void v4UpgradePreservesPublicationAndProvidesAStableRestorableBackup() throws Exception {
    var owner = new Actor("org", "owner");
    List<PublicationVersion> publications;
    List<PublishedEvidence> sources;
    List<String> physicalIds;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, "Published lifecycle fixture.");
      physicalIds = PublishedCorpusFixture.physicalIds(claim);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      publications = scope.publications();
      sources = fixture.evidence.hydrate(scope, physicalIds);
      assertEquals(1, publications.size());
      assertEquals(1, sources.size());
    }

    Path database = directory.resolve("java-library.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      // Restore only the new sidecar/version marker to construct an actual v4 publication fixture.
      statement.execute("DROP TABLE document_tombstones");
      statement.execute("UPDATE format_info SET version=4");
      statement.execute("PRAGMA user_version=4");
    }
    assertEquals(4, scalar(database, "PRAGMA user_version"));

    for (int opening = 0; opening < 2; opening++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
        assertEquals(publications, scope.publications());
        assertEquals(sources, fixture.evidence.hydrate(scope, physicalIds));
        assertEquals(5, scalar(database, "PRAGMA user_version"));
        assertEquals(5, scalar(database, "SELECT version FROM format_info"));
      }
      assertEquals(1, backups().size());
    }

    Path backup = backups().getFirst();
    assertEquals(4, scalar(backup, "PRAGMA user_version"));
    assertEquals(4, scalar(backup, "SELECT version FROM format_info"));
    assertEquals(
        0,
        scalar(
            backup,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='document_tombstones'"));
    assertEquals(1, scalar(backup, "SELECT COUNT(*) FROM active_corpus_publications"));

    Path restoredDirectory = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backup, restoredDirectory.resolve("java-library.db"));
    try (var restored = new PublishedCorpusFixture(restoredDirectory)) {
      var scope = restored.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      assertEquals(publications, scope.publications());
      assertEquals(sources, restored.evidence.hydrate(scope, physicalIds));
    }
    // The independently restored copy cannot modify its immutable v4 source backup.
    assertEquals(4, scalar(backup, "PRAGMA user_version"));
    assertEquals(1, backups().size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DROP TABLE document_tombstones",
        "ALTER TABLE document_tombstones RENAME COLUMN document_id TO missing_document_id",
        "ALTER TABLE document_tombstones RENAME COLUMN workspace_id TO missing_workspace_id",
        "ALTER TABLE document_tombstones RENAME COLUMN requested_by TO missing_requested_by",
        "ALTER TABLE document_tombstones RENAME COLUMN requested_at TO missing_requested_at",
        "DROP TRIGGER document_tombstones_identity",
        "DROP TRIGGER document_tombstones_no_replace",
        "DROP TRIGGER document_tombstones_no_update",
        "DROP TRIGGER document_tombstones_no_delete",
        "DROP TABLE query_traces",
        "DROP TABLE query_trace_documents",
        "DROP TABLE query_trace_evidence",
        "DROP TRIGGER query_traces_complete",
        "DROP TABLE indexing_attempts",
        "DROP TABLE index_publication_entries",
        "ALTER TABLE indexing_jobs RENAME COLUMN projection_generation_id TO missing_generation",
        "ALTER TABLE index_publications RENAME COLUMN projection_generation_id TO missing_generation"
      })
  void damagedV5OrInheritedSchemaIsRejectedBeforeUse(String corruption) throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Only the persisted test fixture is damaged, never a production or historical DDL method.
    }
    sql(corruption);

    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertEquals("Cannot open isolated Java library", failure.getMessage());
    assertNull(failure.getCause());
    assertEquals(5, scalar(directory.resolve("java-library.db"), "PRAGMA user_version"));
    assertEquals(
        5, scalar(directory.resolve("java-library.db"), "SELECT version FROM format_info"));
    assertTrue(backups().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE format_info SET version=4",
        "PRAGMA user_version=4",
        "PRAGMA user_version=6",
        "UPDATE format_info SET format='unrecognized-format'",
        "PRAGMA application_id=1"
      })
  void inconsistentV5FormatMarkersAreRejectedWithoutMigration(String corruption) throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Start from a valid, fully committed v5 database.
    }
    sql(corruption);
    Path database = directory.resolve("java-library.db");
    int versionBefore = scalar(database, "PRAGMA user_version");
    int formatVersionBefore = scalar(database, "SELECT version FROM format_info");

    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertEquals(versionBefore, scalar(database, "PRAGMA user_version"));
    assertEquals(formatVersionBefore, scalar(database, "SELECT version FROM format_info"));
    assertTrue(backups().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE document_tombstones SET requested_at='2026-09-09T00:00:00Z'",
        "DELETE FROM document_tombstones",
        "INSERT OR REPLACE INTO document_tombstones VALUES('removal-document','org','other-owner','2026-09-09T00:00:00Z')"
      })
  void persistedRemovalCannotBeUpdatedDeletedOrReplaced(String mutation) throws Exception {
    createSyntheticDocument();
    try (var store = new SqliteAuthorityStore(directory)) {
      var lifecycle = new DocumentLifecycleRepository(store);
      store.transaction(
          () -> {
            lifecycle.insertRemoval(removal);
            return null;
          });
    }

    assertThrows(SQLException.class, () -> sql(mutation));

    try (var reopened = new SqliteAuthorityStore(directory)) {
      var lifecycle = new DocumentLifecycleRepository(reopened);
      assertEquals(
          removal,
          reopened.transaction(
              () -> lifecycle.findRemoval(owner, removal.documentId()).orElseThrow()));
    }
  }

  @ParameterizedTest
  @CsvSource({"removal-document,other-org", "missing-document,org"})
  void removalIdentityMustMatchAnExistingDocument(String documentId, String workspaceId) {
    createSyntheticDocument();
    try (var store = new SqliteAuthorityStore(directory)) {
      var lifecycle = new DocumentLifecycleRepository(store);
      var invalid =
          new DocumentRemovalEntity(
              documentId, workspaceId, owner.principalId(), removal.requestedAt());

      var failure =
          assertThrows(
              ApplicationException.class,
              () ->
                  store.transaction(
                      () -> {
                        lifecycle.insertRemoval(invalid);
                        return null;
                      }));

      assertEquals("management_unavailable", failure.code());
      assertTrue(store.transaction(() -> lifecycle.findRemoval(owner, documentId).isEmpty()));
      assertTrue(
          store.transaction(
              () -> lifecycle.findRemoval(new Actor(workspaceId, "owner"), documentId).isEmpty()));
      assertTrue(
          store.transaction(
              () -> lifecycle.findWritableDocument(owner, removal.documentId()).isPresent()));
    }
  }

  @Test
  void newV5RemovalSurvivesReopenAndRestrictsPhysicalDocumentDeletion() throws Exception {
    createSyntheticDocument();
    try (var store = new SqliteAuthorityStore(directory)) {
      var lifecycle = new DocumentLifecycleRepository(store);
      store.transaction(
          () -> {
            lifecycle.insertRemoval(removal);
            return null;
          });
    }

    // Synthetic documents have no corpus/source FK; this refusal must come from the new tombstone.
    var failure =
        assertThrows(
            SQLException.class, () -> sql("DELETE FROM documents WHERE id='removal-document'"));
    assertTrue(failure.getMessage().contains("FOREIGN KEY constraint failed"));

    for (int opening = 0; opening < 2; opening++) {
      try (var reopened = new SqliteAuthorityStore(directory)) {
        var lifecycle = new DocumentLifecycleRepository(reopened);
        var management = new ManagementRepository(reopened);
        assertEquals(
            removal,
            reopened.transaction(
                () -> lifecycle.findRemoval(owner, removal.documentId()).orElseThrow()));
        assertTrue(reopened.transaction(() -> management.documentExists(removal.documentId())));
        assertNull(reopened.transaction(() -> management.currentRole(owner, removal.documentId())));
        assertTrue(
            reopened.transaction(
                () ->
                    lifecycle
                        .findRemoval(new Actor("other-org", "owner"), removal.documentId())
                        .isEmpty()));
      }
    }
    assertTrue(backups().isEmpty());
  }

  @Test
  void v5DdlConflictRollsBackTheNewTableAndKeepsItsReadableV4Backup() throws Exception {
    PublicationVersion publication;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(owner, "Retained publication when lifecycle migration fails.");
      publication =
          fixture
              .evidence
              .snapshot(owner, DocumentSelection.allDocuments(), TARGET)
              .publications()
              .getFirst();
    }
    sql("DROP TABLE document_tombstones");
    sql("UPDATE format_info SET version=4");
    sql("PRAGMA user_version=4");
    // The first CREATE TABLE succeeds, then this name collision forces rollback of the entire v5
    // DDL.
    sql(
        "CREATE TRIGGER document_tombstones_identity BEFORE UPDATE ON documents WHEN 0 BEGIN SELECT RAISE(ABORT,'fixture conflict'); END");

    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertNull(failure.getCause());
    Path database = directory.resolve("java-library.db");
    assertEquals(4, scalar(database, "PRAGMA user_version"));
    assertEquals(4, scalar(database, "SELECT version FROM format_info"));
    assertEquals(
        0,
        scalar(
            database,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='document_tombstones'"));
    assertEquals(1, scalar(database, "SELECT COUNT(*) FROM active_corpus_publications"));
    assertEquals(1, backups().size());
    Path backup = backups().getFirst();
    assertEquals(4, scalar(backup, "PRAGMA user_version"));
    assertEquals(4, scalar(backup, "SELECT version FROM format_info"));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
        var statement =
            connection.prepareStatement(
                "SELECT id,manifest_sha256 FROM index_publications WHERE id=?")) {
      statement.setString(1, publication.publicationId());
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals(publication.publicationId(), result.getString("id"));
        assertEquals(publication.manifestSha256(), result.getString("manifest_sha256"));
        assertFalse(result.next());
      }
    }
  }

  private void createSyntheticDocument() {
    try (var authority = new AuthorityTestContext(directory)) {
      authority
          .management()
          .registerSyntheticDocument(
              owner,
              new SyntheticDocument(
                  removal.documentId(),
                  "Lifecycle fixture.txt",
                  "document",
                  "text/plain",
                  "revision-fixture",
                  "a".repeat(64),
                  20),
              Map.of());
    }
  }

  private void sql(String command) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute(command);
    }
  }

  private List<Path> backups() throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(
              path ->
                  path.getFileName().toString().startsWith("java-library.v4-before-v5-")
                      && path.toString().endsWith(".db"))
          .toList();
    }
  }

  private static int scalar(Path database, String command) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var result = statement.executeQuery(command)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }
}
