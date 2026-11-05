package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real persisted format checks through the public authority-store lifecycle. */
class DocumentLifecycleSchemaTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private final DocumentRemovalEntity removal =
      new DocumentRemovalEntity("removal-document", "org", "owner", "2026-09-08T00:00:00Z");

  @Test
  void newAuthorityHasTheLifecycleFormat() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    try (var database =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = database.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(LibrarySchema.VERSION, version.getInt(1));
      }
      try (var format = statement.executeQuery("SELECT version FROM format_info")) {
        assertTrue(format.next());
        assertEquals(LibrarySchema.VERSION, format.getInt(1));
      }
    }
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
  void newRemovalSurvivesReopenAndRestrictsPhysicalDocumentDeletion() throws Exception {
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
}
