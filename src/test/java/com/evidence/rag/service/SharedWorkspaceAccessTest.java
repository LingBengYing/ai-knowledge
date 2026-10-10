package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static com.evidence.rag.support.PublishedCorpusFixture.physicalIds;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import com.evidence.rag.support.DocumentWithdrawal;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 0053: authenticated organization membership replaces historical per-document ACL roles. */
class SharedWorkspaceAccessTest {
  @TempDir Path directory;
  private final Actor creator = new Actor("org", "creator");
  private final Actor member = new Actor("org", "second-member-with-no-acl");
  private final Actor foreign = new Actor("other-org", "creator");

  @Test
  void secondMemberReadsOriginalRetrievesAndEditsWithoutAddingAclRows() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var published = fixture.publish(creator, "Shared project evidence.");
      fixture.publish(foreign, "Foreign project evidence.");
      assertEquals(1L, fixture.authority.listDocuments(member, Map.of()).get("total"));
      var scope = fixture.evidence.snapshot(member, DocumentSelection.allDocuments(), TARGET);
      assertEquals(1, scope.publications().size());
      assertEquals(published.documentId(), scope.publications().getFirst().documentId());
      assertEquals(1, fixture.evidence.hydrate(scope, physicalIds(published)).size());
      var repository = new ManagementRepository(fixture.authority.store());
      var original =
          fixture
              .authority
              .store()
              .transaction(
                  () ->
                      repository
                          .findDocumentOriginal(member, published.documentId())
                          .orElseThrow());
      assertArrayEquals(
          "Shared project evidence.".getBytes(StandardCharsets.UTF_8), original.content());
      assertEquals(
          "Shared title",
          fixture
              .authority
              .updateDocument(
                  member, published.documentId(), Map.of("display_name", "Shared title"))
              .get("display_name"));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.authority.updateDocument(
                  foreign, published.documentId(), Map.of("display_name", "Wrong organization")));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement(
                  "SELECT count(*) FROM document_acl WHERE principal_id=?")) {
        statement.setString(1, member.principalId());
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1));
        }
      }
    }
  }

  @Test
  void secondMemberManagesAnotherMembersFoldersAndBackgroundTasks() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var folder = fixture.authority.createFolder(creator, Map.of("name", "Shared folder"));
      assertEquals(
          "Renamed",
          fixture
              .authority
              .renameFolder(member, (String) folder.get("folder_id"), Map.of("name", "Renamed"))
              .get("name"));
      var task =
          fixture.authority.uploadDocument(creator, "pending.txt", "text/plain", new byte[] {65});
      String taskId = (String) task.get("task_id");
      assertNotNull(fixture.authority.ingestionStatus(member, taskId));
      assertEquals("cancelled", fixture.authority.cancelIngestion(member, taskId).get("status"));
      assertThrows(
          ApplicationException.class, () -> fixture.authority.ingestionStatus(foreign, taskId));
    }
  }

  @Test
  void modelSettingsAreSharedButStillRequireTheAuthenticatedOrganization() {
    var policy = new ModelConfigurationPermissionPolicy("org", Set.of("original-operator"));
    assertTrue(policy.canEdit(member));
    assertDoesNotThrow(() -> policy.requireEdit(member));
    assertFalse(policy.canEdit(foreign));
    assertThrows(ApplicationException.class, () -> policy.requireRead(foreign));
    assertThrows(ApplicationException.class, () -> policy.requireEdit(null));
  }

  @Test
  void backgroundWorkDoesNotDependOnLegacyAclAndRemovedDocumentsStayUnavailable() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var task =
          fixture
              .authority
              .ingestion()
              .uploadDocument(
                  creator,
                  "shared.txt",
                  "text/plain",
                  "Shared background material".getBytes(StandardCharsets.UTF_8));
      var claim = fixture.authority.ingestion().claimIngestion(creator.workspaceId()).orElseThrow();
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement("DELETE FROM document_acl WHERE document_id=?")) {
        statement.setString(1, task.documentId());
        statement.executeUpdate();
      }
      assertTrue(fixture.authority.ingestion().isIngestionClaimCurrent(claim));
      assertTrue(
          fixture
              .authority
              .ingestion()
              .completeIngestion(
                  claim, new TextParser().parse("shared.txt", "text/plain", claim.content())));
      var store = fixture.authority.store();
      DocumentWithdrawal.withdraw(store, member, task.documentId());
      assertEquals(0L, fixture.authority.listDocuments(member, Map.of()).get("total"));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.authority.updateDocument(
                  member, task.documentId(), Map.of("display_name", "Must remain removed")));
      assertTrue(
          store.transaction(
              () ->
                  new ManagementRepository(store)
                      .findDocumentOriginal(member, task.documentId())
                      .isEmpty()));
    }
  }
}
