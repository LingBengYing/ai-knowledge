package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.AuditEventResult;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.dto.DocumentRemovalResult;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentLifecycleServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void withdrawalHidesTheQueuedUploadBeforePaginationWithoutErasingItsOriginalOrIdentity() {
    byte[] original = "仅用于生命周期测试的原始内容。".getBytes(StandardCharsets.UTF_8);
    byte[] retained = "另一份仍然可见的合成资料。".getBytes(StandardCharsets.UTF_8);
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var lifecycleRepository = new DocumentLifecycleRepository(store);
      var managementRepository = new ManagementRepository(store);
      var ingestionRepository = new IngestionRepository(store);
      var lifecycle =
          new DocumentLifecycleService(
              store,
              lifecycleRepository,
              managementRepository,
              ingestionRepository,
              new IndexingRepository(store),
              new DocumentPermissionPolicy());
      var removed =
          authority.ingestion().uploadDocument(owner, "A removed.txt", "text/plain", original);
      var visible =
          authority.ingestion().uploadDocument(owner, "B visible.txt", "text/plain", retained);
      var query = new DocumentQuery("", null, null, null, null, "name_asc", 1, 1);
      var before = authority.management().listDocuments(owner, query);
      assertEquals(2, before.total());
      assertEquals(removed.documentId(), before.items().getFirst().documentId());
      var originalIdentity =
          store.transaction(
              () ->
                  lifecycleRepository
                      .findWritableDocument(owner, removed.documentId())
                      .orElseThrow());

      var receipt = lifecycle.removeDocument(owner, removed.documentId());

      assertEquals("deleting", receipt.status());
      assertEquals("pending", receipt.cleanupStatus());
      assertAll(
          () -> {
            var after = authority.management().listDocuments(owner, query);
            assertEquals(1, after.total());
            assertEquals(1, after.totalPages());
            assertEquals(
                List.of(visible.documentId()),
                after.items().stream().map(document -> document.documentId()).toList());
          },
          () ->
              assertNull(
                  store.transaction(
                      () -> managementRepository.currentRole(owner, removed.documentId()))),
          () -> {
            var failure =
                assertThrows(
                    ApplicationException.class,
                    () ->
                        authority
                            .management()
                            .updateDocument(
                                owner,
                                removed.documentId(),
                                new DocumentPatchCommand(
                                    true, "Must not update", false, null, false, null)));
            assertEquals(FailureKind.NOT_FOUND, failure.kind());
            assertEquals("not_found", failure.code());
          },
          () -> {
            assertArrayEquals(
                original,
                store.transaction(() -> ingestionRepository.original(removed.documentId())));
            var identity =
                store.transaction(
                    () ->
                        lifecycleRepository
                            .findWritableDocument(owner, removed.documentId())
                            .orElseThrow());
            assertEquals(originalIdentity.filename(), identity.filename());
            assertEquals(originalIdentity.sourceSha256(), identity.sourceSha256());
            assertEquals(originalIdentity.sizeBytes(), identity.sizeBytes());
            assertEquals(originalIdentity.mimeType(), identity.mimeType());
            assertEquals(
                originalIdentity.registrationRevisionId(), identity.registrationRevisionId());
            assertEquals(
                (long) original.length + retained.length,
                store.transaction(() -> ingestionRepository.storedBytes(owner.workspaceId())));
          });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"owner", "editor"})
  void repeatedWithdrawalRequiresCurrentWritePermissionAndKeepsItsOriginalAudit(String principal)
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      authority
          .management()
          .registerSyntheticDocument(
              owner,
              new SyntheticDocument(
                  "acl-removal",
                  "Synthetic ACL.txt",
                  "document",
                  "text/plain",
                  "revision-acl",
                  "a".repeat(64),
                  20),
              Map.of("editor", "editor", "reader", "reader"));
      var lifecycle = lifecycle(authority);
      var caller = new Actor(owner.workspaceId(), principal);
      var unauthorized =
          List.of(
              new Actor(owner.workspaceId(), "reader"),
              new Actor(owner.workspaceId(), "stranger"),
              new Actor("other-org", principal));
      for (var actor : unauthorized) {
        notFound(() -> lifecycle.removeDocument(actor, "acl-removal"));
      }
      notFound(() -> lifecycle.removeDocument(caller, "missing-document"));
      assertTrue(authority.management().auditEvents(caller).isEmpty());

      var original = lifecycle.removeDocument(caller, "acl-removal");
      var audit = authority.management().auditEvents(caller);
      assertEquals(1, audit.size());
      assertEquals("document_removal_requested", audit.getFirst().action());
      assertEquals(original, lifecycle.removeDocument(caller, "acl-removal"));
      for (var actor : unauthorized) {
        notFound(() -> lifecycle.removeDocument(actor, "acl-removal"));
      }
      assertEquals(audit, authority.management().auditEvents(caller));

      sql(
          "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?",
          "acl-removal",
          principal);
      notFound(() -> lifecycle.removeDocument(caller, "acl-removal"));
      sql(
          "DELETE FROM document_acl WHERE document_id=? AND principal_id=?",
          "acl-removal",
          principal);
      notFound(() -> lifecycle.removeDocument(caller, "acl-removal"));
      assertEquals(audit, authority.management().auditEvents(caller));

      sql(
          "INSERT INTO document_acl(document_id,principal_id,role) VALUES(?,?,?)",
          "acl-removal",
          principal,
          principal);
      assertEquals(original, lifecycle.removeDocument(caller, "acl-removal"));
      assertEquals(audit, authority.management().auditEvents(caller));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"queued", "processing", "failed", "cancelled", "parsed"})
  void withdrawalCancelsOnlyActiveIngestionsAndPreservesTerminalHistory(String state) {
    var content = "合成资料的生命周期状态测试。".getBytes(StandardCharsets.UTF_8);
    try (var authority = new AuthorityTestContext(directory)) {
      var task = authority.ingestion().uploadDocument(owner, "states.txt", "text/plain", content);
      IngestionClaim claim =
          "queued".equals(state)
              ? null
              : authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      switch (state) {
        case "failed" -> assertTrue(authority.ingestion().failIngestion(claim, "parser_failed"));
        case "cancelled" -> authority.ingestion().cancelIngestion(owner, task.taskId());
        case "parsed" ->
            assertTrue(authority.ingestion().completeIngestion(claim, parsed(content)));
        default -> {}
      }
      var repository = new IngestionRepository(authority.store());
      var before =
          authority
              .store()
              .transaction(() -> repository.findInternalTask(task.taskId()).orElseThrow());
      var auditBefore = authority.management().auditEvents(owner).size();
      var receipt = lifecycle(authority).removeDocument(owner, task.documentId());
      var after =
          authority
              .store()
              .transaction(() -> repository.findInternalTask(task.taskId()).orElseThrow());
      boolean active = Set.of("queued", "processing").contains(state);
      if (active) {
        assertEquals("cancelled", after.state());
        assertNull(after.claimTokenSha256());
        assertNull(after.errorCode());
        assertEquals(before.revisionId(), after.revisionId());
        assertEquals(before.attempt(), after.attempt());
      } else {
        assertEquals(before, after);
      }
      assertEquals(
          auditBefore + (active ? 2 : 1), authority.management().auditEvents(owner).size());
      assertEquals(receipt, lifecycle(authority).removeDocument(owner, task.documentId()));
      assertEquals(
          auditBefore + (active ? 2 : 1), authority.management().auditEvents(owner).size());
      notFound(() -> authority.ingestion().ingestionStatus(owner, task.taskId()));
      notFound(() -> authority.ingestion().retryIngestion(owner, task.taskId()));
      notFound(() -> authority.ingestion().cancelIngestion(owner, task.taskId()));
      notFound(() -> authority.ingestion().parsedEvidence(owner, task.documentId()));
      assertTrue(authority.ingestion().claimIngestion(owner.workspaceId()).isEmpty());
      if (claim != null) {
        assertFalse(authority.ingestion().isIngestionClaimCurrent(claim));
        assertFalse(authority.ingestion().completeIngestion(claim, parsed(content)));
        assertFalse(authority.ingestion().failIngestion(claim, "parser_failed"));
      }
      if ("parsed".equals(state)) {
        assertEquals(
            parsed(content),
            authority.store().transaction(() -> repository.parsedEvidence(task.revisionId())));
      }
      authority.ingestion().recoverIngestions();
      assertEquals(
          after,
          authority
              .store()
              .transaction(() -> repository.findInternalTask(task.taskId()).orElseThrow()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"queued", "processing", "failed", "cancelled", "indexed"})
  void withdrawalCancelsOnlyActiveIndexesAndKeepsPublishedEvidenceImmutable(String state) {
    try (var authority = new AuthorityTestContext(directory)) {
      var upload = parsedUpload(authority);
      var target = new IndexTarget("embedding-fixture", "b".repeat(64), "fixture-v1", 2);
      var task = authority.indexing().createIndexing(owner, upload.documentId(), target);
      IndexClaim claim =
          "queued".equals(state)
              ? null
              : authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow();
      switch (state) {
        case "failed" -> assertTrue(authority.indexing().failIndexing(claim, "indexing_failed"));
        case "cancelled" -> authority.indexing().cancelIndexing(owner, task.taskId());
        case "indexed" ->
            assertTrue(
                authority.indexing().completeIndexing(claim, digests(claim), verified(claim)));
        default -> {}
      }
      var repository = new IndexingRepository(authority.store());
      var before =
          authority
              .store()
              .transaction(() -> repository.findInternalTask(task.taskId()).orElseThrow());
      var publication =
          authority.store().transaction(() -> repository.publicationId(task.taskId()));
      var source = authority.ingestion().parsedEvidence(owner, upload.documentId());
      int auditBefore = authority.management().auditEvents(owner).size();

      lifecycle(authority).removeDocument(owner, task.documentId());

      var after =
          authority
              .store()
              .transaction(() -> repository.findInternalTask(task.taskId()).orElseThrow());
      boolean active = Set.of("queued", "processing").contains(state);
      if (active) {
        assertEquals("cancelled", after.state());
        assertNull(after.claimTokenSha256());
        assertNull(after.errorCode());
        assertEquals(before.revisionId(), after.revisionId());
        assertEquals(before.projectionGenerationId(), after.projectionGenerationId());
        assertEquals(before.attempt(), after.attempt());
      } else {
        assertEquals(before, after);
      }
      assertEquals(
          auditBefore + (active ? 2 : 1), authority.management().auditEvents(owner).size());
      assertEquals(
          publication,
          authority.store().transaction(() -> repository.publicationId(task.taskId())));
      assertEquals(
          source,
          authority
              .store()
              .transaction(
                  () ->
                      new IngestionRepository(authority.store())
                          .parsedEvidence(upload.revisionId())));
      notFound(() -> authority.indexing().indexingStatus(owner, task.taskId()));
      notFound(() -> authority.indexing().retryIndexing(owner, task.taskId(), target));
      notFound(() -> authority.indexing().cancelIndexing(owner, task.taskId()));
      notFound(() -> authority.indexing().createIndexing(owner, task.documentId(), target));
      assertTrue(authority.indexing().claimIndexing(owner.workspaceId()).isEmpty());
      if (claim != null) {
        assertFalse(authority.indexing().isIndexingClaimCurrent(claim));
        assertFalse(authority.indexing().completeIndexing(claim, digests(claim), verified(claim)));
        assertFalse(authority.indexing().failIndexing(claim, "indexing_failed"));
      }
      authority.indexing().recoverIndexings();
      assertEquals(
          after,
          authority
              .store()
              .transaction(() -> repository.findInternalTask(task.taskId()).orElseThrow()));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "ingestion, cancellation_update",
    "ingestion, cancellation_audit",
    "ingestion, removal_audit",
    "indexing, cancellation_update",
    "indexing, cancellation_audit",
    "indexing, removal_audit"
  })
  void cancellationAndAuditFailuresRollBackTheWholeWithdrawalAndAllowAnExplicitRetry(
      String kind, String failurePoint) throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      byte[] content = "回滚测试的合成原文。".getBytes(StandardCharsets.UTF_8);
      boolean indexing = "indexing".equals(kind);
      var upload =
          indexing
              ? parsedUpload(authority)
              : authority.ingestion().uploadDocument(owner, "states.txt", "text/plain", content);
      var target = new IndexTarget("embedding-fixture", "b".repeat(64), "fixture-v1", 2);
      var task =
          indexing
              ? authority.indexing().createIndexing(owner, upload.documentId(), target)
              : upload;
      var parseClaim =
          indexing ? null : authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      var indexClaim =
          indexing ? authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow() : null;
      var folder = authority.management().createFolder(owner, "Rollback folder");
      authority
          .management()
          .updateDocument(
              owner,
              upload.documentId(),
              new DocumentPatchCommand(
                  false, null, true, folder.folderId(), true, List.of("retained-tag")));
      var query = new DocumentQuery("", null, null, null, null, "name_asc", 1, 20);
      var listing = authority.management().listDocuments(owner, query);
      var audit = authority.management().auditEvents(owner);
      var lifecycle = lifecycle(authority);
      var removalRepository = new DocumentLifecycleRepository(authority.store());
      String operation =
          "cancellation_update".equals(failurePoint)
              ? "BEFORE UPDATE OF state ON " + kind + "_jobs WHEN NEW.state='cancelled'"
              : "BEFORE INSERT ON management_audit WHEN NEW.action='"
                  + ("cancellation_audit".equals(failurePoint)
                      ? kind + "_cancelled"
                      : "document_removal_requested")
                  + "'";
      sql(
          "CREATE TRIGGER injected_removal_failure "
              + operation
              + " BEGIN SELECT RAISE(ABORT,'synthetic_private_removal_failure'); END");
      try {
        var failure =
            assertThrows(
                ApplicationException.class,
                () -> lifecycle.removeDocument(owner, upload.documentId()));
        assertEquals(FailureKind.UNAVAILABLE, failure.kind());
        assertEquals("management_unavailable", failure.code());
        assertFalse(failure.getMessage().contains("synthetic_private_removal_failure"));
        assertNull(failure.getCause());
      } finally {
        sql("DROP TRIGGER injected_removal_failure");
      }
      assertTrue(
          authority
              .store()
              .transaction(
                  () -> removalRepository.findRemoval(owner, upload.documentId()).isEmpty()));
      assertEquals(listing, authority.management().listDocuments(owner, query));
      assertEquals(audit, authority.management().auditEvents(owner));
      assertEquals(1, authority.management().listFolders(owner).getFirst().documentCount());
      assertEquals(List.of("retained-tag"), authority.management().listTags(owner));
      if (indexing) {
        assertTrue(authority.indexing().isIndexingClaimCurrent(indexClaim));
        assertEquals(
            "processing", authority.indexing().indexingStatus(owner, task.taskId()).state());
      } else {
        assertTrue(authority.ingestion().isIngestionClaimCurrent(parseClaim));
        assertEquals(
            "processing", authority.ingestion().ingestionStatus(owner, task.taskId()).state());
      }

      var retried = lifecycle.removeDocument(owner, upload.documentId());
      assertEquals("deleting", retried.status());
      assertEquals("pending", retried.cleanupStatus());
      assertEquals(0, authority.management().listDocuments(owner, query).total());
      assertEquals(0, authority.management().listFolders(owner).getFirst().documentCount());
      assertEquals(audit.size() + 3, authority.management().auditEvents(owner).size());
      assertEquals(retried, lifecycle.removeDocument(owner, upload.documentId()));
      assertEquals(audit.size() + 3, authority.management().auditEvents(owner).size());
      if (indexing) {
        assertFalse(authority.indexing().isIndexingClaimCurrent(indexClaim));
      } else {
        assertFalse(authority.ingestion().isIngestionClaimCurrent(parseClaim));
      }
    }
  }

  @Test
  void withdrawnDocumentsDoNotLeakFolderVisibilityOrTagsAndNoLongerBlockFolderRemoval() {
    try (var authority = new AuthorityTestContext(directory)) {
      byte[] content = "目录和标签合成资料。".getBytes(StandardCharsets.UTF_8);
      var removed =
          authority.ingestion().uploadDocument(owner, "removed.txt", "text/plain", content);
      var retained =
          authority.ingestion().uploadDocument(owner, "retained.txt", "text/plain", content);
      var managementRepository = new ManagementRepository(authority.store());
      authority
          .store()
          .transaction(
              () -> {
                managementRepository.insertGrant(removed.documentId(), "reader", "reader");
                managementRepository.insertGrant(retained.documentId(), "reader", "reader");
                return null;
              });
      var removedFolder = authority.management().createFolder(owner, "Removed folder");
      var retainedFolder = authority.management().createFolder(owner, "Retained folder");
      authority
          .management()
          .updateDocument(
              owner,
              removed.documentId(),
              new DocumentPatchCommand(
                  false, null, true, removedFolder.folderId(), true, List.of("removed-only")));
      authority
          .management()
          .updateDocument(
              owner,
              retained.documentId(),
              new DocumentPatchCommand(
                  false, null, true, retainedFolder.folderId(), true, List.of("retained-only")));
      var reader = new Actor(owner.workspaceId(), "reader");
      assertEquals(2, authority.management().listFolders(reader).size());
      assertEquals(
          List.of("removed-only", "retained-only"), authority.management().listTags(reader));

      lifecycle(authority).removeDocument(owner, removed.documentId());

      assertEquals(
          List.of(retainedFolder.folderId()),
          authority.management().listFolders(reader).stream()
              .map(folder -> folder.folderId())
              .toList());
      assertEquals(List.of("retained-only"), authority.management().listTags(reader));
      assertEquals(List.of("retained-only"), authority.management().listTags(owner));
      var ownerFolders = authority.management().listFolders(owner);
      assertEquals(2, ownerFolders.size());
      assertEquals(
          0,
          ownerFolders.stream()
              .filter(folder -> folder.folderId().equals(removedFolder.folderId()))
              .findFirst()
              .orElseThrow()
              .documentCount());
      assertEquals(
          1,
          ownerFolders.stream()
              .filter(folder -> folder.folderId().equals(retainedFolder.folderId()))
              .findFirst()
              .orElseThrow()
              .documentCount());
      assertNull(
          authority
              .store()
              .transaction(
                  () ->
                      new DocumentLifecycleRepository(authority.store())
                          .findWritableDocument(owner, removed.documentId())
                          .orElseThrow()
                          .folderId()));
      assertEquals(
          "removed", authority.management().removeFolder(owner, removedFolder.folderId()).status());
      assertEquals(
          List.of(retainedFolder.folderId()),
          authority.management().listFolders(owner).stream()
              .map(folder -> folder.folderId())
              .toList());
      var visible =
          authority
              .management()
              .listDocuments(
                  reader, new DocumentQuery("", null, null, null, null, "name_asc", 1, 20));
      assertEquals(1, visible.total());
      assertEquals(retained.documentId(), visible.items().getFirst().documentId());
      assertEquals(retainedFolder.folderId(), visible.items().getFirst().folderId());
      assertEquals(List.of("retained-only"), visible.items().getFirst().tags());
    }
  }

  @Test
  void sameContentUploadGetsNewIdentitiesAndOnlyPendingQuotaIsReleasedByWithdrawal() {
    byte[] content = "相同内容仍是新资料。".getBytes(StandardCharsets.UTF_8);
    try (var authority = new AuthorityTestContext(directory)) {
      var tasks = new ArrayList<TaskResult>();
      for (int index = 0; index < 32; index++) {
        tasks.add(authority.ingestion().uploadDocument(owner, "same.txt", "text/plain", content));
      }
      var full =
          assertThrows(
              ApplicationException.class,
              () -> authority.ingestion().uploadDocument(owner, "same.txt", "text/plain", content));
      assertEquals("ingestion_quota_exceeded", full.code());
      var removed = tasks.getFirst();
      lifecycle(authority).removeDocument(owner, removed.documentId());
      var replacement =
          authority.ingestion().uploadDocument(owner, "same.txt", "text/plain", content);
      assertNotEquals(removed.documentId(), replacement.documentId());
      assertNotEquals(removed.revisionId(), replacement.revisionId());
      assertNotEquals(removed.taskId(), replacement.taskId());
      assertEquals("queued", replacement.state());
      var ingestion = new IngestionRepository(authority.store());
      assertArrayEquals(
          content, authority.store().transaction(() -> ingestion.original(removed.documentId())));
      assertArrayEquals(
          content,
          authority.store().transaction(() -> ingestion.original(replacement.documentId())));
      long bytes = authority.store().transaction(() -> ingestion.storedBytes(owner.workspaceId()));
      assertEquals(33L * content.length, bytes);
      var listed =
          authority
              .management()
              .listDocuments(
                  owner, new DocumentQuery("", null, null, null, null, "name_asc", 1, 100));
      assertEquals(32, listed.total());
      assertFalse(
          listed.items().stream().anyMatch(item -> item.documentId().equals(removed.documentId())));
      assertTrue(
          listed.items().stream()
              .anyMatch(item -> item.documentId().equals(replacement.documentId())));
      var stillFull =
          assertThrows(
              ApplicationException.class,
              () -> authority.ingestion().uploadDocument(owner, "same.txt", "text/plain", content));
      assertEquals("ingestion_quota_exceeded", stillFull.code());
      var duplicateIdentity =
          assertThrows(
              ApplicationException.class,
              () ->
                  authority
                      .management()
                      .registerSyntheticDocument(
                          owner,
                          new SyntheticDocument(
                              removed.documentId(),
                              "same.txt",
                              "document",
                              "text/plain",
                              removed.revisionId(),
                              "a".repeat(64),
                              content.length),
                          Map.of()));
      assertEquals("document_conflict", duplicateIdentity.code());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ingestion", "indexing"})
  void reopeningRetainsWithdrawalReceiptAndDoesNotReviveCancelledClaims(String kind) {
    boolean indexing = "indexing".equals(kind);
    byte[] content = "重开后仍撤下的合成资料。".getBytes(StandardCharsets.UTF_8);
    TaskResult upload;
    IngestionClaim parseClaim;
    IndexClaim indexClaim;
    DocumentRemovalResult receipt;
    List<AuditEventResult> audit;
    try (var authority = new AuthorityTestContext(directory)) {
      upload =
          indexing
              ? parsedUpload(authority)
              : authority.ingestion().uploadDocument(owner, "states.txt", "text/plain", content);
      parseClaim =
          indexing ? null : authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      if (indexing) {
        authority
            .indexing()
            .createIndexing(
                owner,
                upload.documentId(),
                new IndexTarget("embedding-fixture", "b".repeat(64), "fixture-v1", 2));
        indexClaim = authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow();
      } else {
        indexClaim = null;
      }
      receipt = lifecycle(authority).removeDocument(owner, upload.documentId());
      audit = authority.management().auditEvents(owner);
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      assertEquals(receipt, lifecycle(reopened).removeDocument(owner, upload.documentId()));
      assertEquals(audit, reopened.management().auditEvents(owner));
      assertEquals(
          0,
          reopened
              .management()
              .listDocuments(
                  owner, new DocumentQuery("", null, null, null, null, "name_asc", 1, 20))
              .total());
      assertTrue(reopened.ingestion().claimIngestion(owner.workspaceId()).isEmpty());
      assertTrue(reopened.indexing().claimIndexing(owner.workspaceId()).isEmpty());
      notFound(() -> reopened.ingestion().ingestionStatus(owner, upload.taskId()));
      if (indexing) {
        assertFalse(reopened.indexing().isIndexingClaimCurrent(indexClaim));
        assertFalse(
            reopened
                .indexing()
                .completeIndexing(indexClaim, digests(indexClaim), verified(indexClaim)));
        assertFalse(reopened.indexing().failIndexing(indexClaim, "indexing_failed"));
        notFound(
            () ->
                reopened.indexing().retryIndexing(owner, indexClaim.jobId(), indexClaim.target()));
      } else {
        assertFalse(reopened.ingestion().isIngestionClaimCurrent(parseClaim));
        assertFalse(reopened.ingestion().completeIngestion(parseClaim, parsed(content)));
        assertFalse(reopened.ingestion().failIngestion(parseClaim, "parser_failed"));
        notFound(() -> reopened.ingestion().retryIngestion(owner, upload.taskId()));
      }
      assertEquals(audit, reopened.management().auditEvents(owner));
      var bytes =
          reopened
              .store()
              .transaction(
                  () -> new IngestionRepository(reopened.store()).original(upload.documentId()));
      if (indexing) {
        assertEquals("仅用于生命周期的真实解析文本。", new String(bytes, StandardCharsets.UTF_8));
      } else {
        assertArrayEquals(content, bytes);
      }
    }
  }

  private TaskResult parsedUpload(AuthorityTestContext authority) {
    byte[] content = "仅用于生命周期的真实解析文本。".getBytes(StandardCharsets.UTF_8);
    var task = authority.ingestion().uploadDocument(owner, "states.txt", "text/plain", content);
    var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(authority.ingestion().completeIngestion(claim, parsed(content)));
    return task;
  }

  private static ParsedText parsed(byte[] content) {
    return new TextParser().parse("states.txt", "text/plain", content);
  }

  private static Map<String, String> digests(IndexClaim claim) {
    var result = new TreeMap<String, String>();
    for (var segment : claim.segments()) {
      result.put(
          RetrievalProjection.physicalSegmentId(
              claim.projectionGenerationId(), segment.segmentId()),
          "a".repeat(64));
    }
    return result;
  }

  private static VerifiedRevision verified(IndexClaim claim) {
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.workspaceId(),
            claim.documentId(),
            claim.projectionGenerationId(),
            digests(claim));
    return new VerifiedRevision(
        claim.target().projectionIdentity(), manifest.sha256(), digests(claim).size());
  }

  private DocumentLifecycleService lifecycle(AuthorityTestContext authority) {
    var store = authority.store();
    return new DocumentLifecycleService(
        store,
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new IngestionRepository(store),
        new IndexingRepository(store),
        new DocumentPermissionPolicy());
  }

  private static void notFound(Runnable action) {
    var failure = assertThrows(ApplicationException.class, action::run);
    assertEquals(FailureKind.NOT_FOUND, failure.kind());
    assertEquals("not_found", failure.code());
  }

  private void sql(String statement, String... values) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var query = connection.prepareStatement(statement)) {
      for (int index = 0; index < values.length; index++) {
        query.setString(index + 1, values[index]);
      }
      query.executeUpdate();
    }
  }
}
