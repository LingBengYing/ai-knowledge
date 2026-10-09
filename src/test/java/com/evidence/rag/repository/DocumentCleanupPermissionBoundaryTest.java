package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.CleanupResource;
import com.evidence.rag.model.domain.DocumentCleanupState;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real SQLite and public control flow; synthetic cleanup adapters never contact a provider. */
class DocumentCleanupPermissionBoundaryTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final Actor EDITOR = new Actor("org-main", "editor");
  private static final String NOW = "2026-10-03T12:00:00Z";
  @TempDir Path directory;

  @Test
  void acceptedRemovalDetachesFolderOnceAndPreservesManualMetadataAndOriginalIdentity() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String id = parsed(authority, "folder-owned synthetic payload");
      String folder = authority.management().createFolder(OWNER, "Retained folder").folderId();
      authority
          .management()
          .updateDocument(
              OWNER,
              id,
              new DocumentPatchCommand(
                  true, "Manual title", true, folder, true, List.of("manual")));
      var before =
          store.transaction(
              () ->
                  store.rows(
                      "SELECT filename,active_revision_id,source_sha256,size_bytes,display_name FROM documents WHERE id=?",
                      id));
      long auditBefore =
          scalar(
              store,
              "SELECT COUNT(*) FROM management_audit WHERE entity_id=? AND action='document_updated'",
              id);
      var service = localService(store);
      var accepted = service.request(OWNER, id);
      assertEquals(accepted, service.request(OWNER, id));
      assertEquals(
          before,
          store.transaction(
              () ->
                  store.rows(
                      "SELECT filename,active_revision_id,source_sha256,size_bytes,display_name FROM documents WHERE id=?",
                      id)));
      assertNull(
          store.transaction(
              () ->
                  store
                      .rows("SELECT folder_id FROM documents WHERE id=?", id)
                      .getFirst()
                      .get("folder_id")));
      assertEquals(
          1,
          scalar(
              store,
              "SELECT COUNT(*) FROM document_tags WHERE document_id=? AND tag='manual'",
              id));
      assertEquals(
          auditBefore + 1,
          scalar(
              store,
              "SELECT COUNT(*) FROM management_audit WHERE entity_id=? AND action='document_updated'",
              id));
      assertEquals(
          1, scalar(store, "SELECT COUNT(*) FROM document_tombstones WHERE document_id=?", id));
      assertTrue(
          scalar(
                  store,
                  "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?",
                  id)
              > 0);
    }
  }

  @Test
  void batchPreservesCompleteOrderAndCurrentEditorScopeWithoutWithdrawingBusyOrHiddenRows() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String accepted = parsed(authority, "editable material");
      String hidden = parsed(authority, "reader material");
      String queued =
          authority
              .ingestion()
              .uploadDocument(OWNER, "queued.txt", "text/plain", new byte[] {65})
              .documentId();
      store.transaction(
          () -> {
            var management = new ManagementRepository(store);
            management.insertGrant(accepted, EDITOR.principalId(), "editor");
            management.insertGrant(hidden, EDITOR.principalId(), "reader");
            management.insertGrant(queued, EDITOR.principalId(), "editor");
            return null;
          });
      var service = localService(store);
      List<String> requested = List.of(hidden, accepted, "missing", queued);
      var result = service.batch(EDITOR, requested);
      assertEquals(4, result.total());
      assertEquals(requested, result.items().stream().map(item -> item.documentId()).toList());
      assertEquals(
          List.of("not_found", "accepted", "not_found", "busy"),
          result.items().stream().map(item -> item.status()).toList());
      assertEquals("not_found", result.items().getFirst().errorCode());
      assertEquals("document_busy", result.items().getLast().errorCode());
      assertNull(result.items().getLast().cleanup());
      assertEquals(1, scalar(store, "SELECT COUNT(*) FROM document_cleanups"));
      assertEquals(
          0,
          scalar(
              store,
              "SELECT COUNT(*) FROM document_tombstones WHERE document_id IN (?,?)",
              hidden,
              queued));
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () -> service.status(new Actor("another-org", EDITOR.principalId()), accepted))
              .code());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "empty", "duplicate", "too_many", "bad_tail", "null_tail"})
  void entireBatchIsValidatedBeforeTheFirstOtherwiseWritableDocumentIsChanged(String shape) {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String id = parsed(authority, "must survive malformed batch");
      List<String> request =
          switch (shape) {
            case "null" -> null;
            case "empty" -> List.of();
            case "duplicate" -> List.of(id, id);
            case "too_many" -> {
              var ids = new ArrayList<String>();
              ids.add(id);
              IntStream.range(0, 100).forEach(i -> ids.add("missing-" + i));
              yield ids;
            }
            case "bad_tail" -> List.of(id, "x".repeat(101));
            case "null_tail" -> Arrays.asList(id, null);
            default -> throw new AssertionError(shape);
          };
      var service = localService(store);
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> service.batch(OWNER, request)).code());
      assertEquals(0, scalar(store, "SELECT COUNT(*) FROM document_cleanups"));
      assertEquals(0, scalar(store, "SELECT COUNT(*) FROM document_tombstones"));
      assertArrayEquals(
          "must survive malformed batch".getBytes(StandardCharsets.UTF_8),
          store.transaction(() -> new IngestionRepository(store).original(id)));
    }
  }

  @Test
  void currentAclIsAppliedBeforePaginationAndARevokedReceiptCannotBeReplayed() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var service = localService(store);
      var ids = new ArrayList<String>();
      for (int i = 0; i < 3; i++) {
        String id = parsed(authority, "page material " + i);
        ids.add(id);
        store.transaction(
            () -> {
              new ManagementRepository(store).insertGrant(id, EDITOR.principalId(), "editor");
              return null;
            });
        service.request(OWNER, id);
      }
      store.transaction(
          () -> {
            store.execute(
                "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?",
                ids.getFirst(),
                EDITOR.principalId());
            return null;
          });
      var first = service.list(EDITOR, 1, 1);
      var second = service.list(EDITOR, 2, 1);
      assertEquals(2, first.total());
      assertEquals(2, second.total());
      assertEquals(1, first.items().size());
      assertEquals(1, second.items().size());
      assertNotEquals(
          first.items().getFirst().documentId(), second.items().getFirst().documentId());
      assertFalse(first.items().getFirst().documentId().equals(ids.getFirst()));
      assertFalse(second.items().getFirst().documentId().equals(ids.getFirst()));
      assertTrue(service.list(EDITOR, Integer.MAX_VALUE, 100).items().isEmpty());
      assertEquals(0, service.list(new Actor("foreign", EDITOR.principalId()), 1, 100).total());
      assertEquals(
          "not_found",
          assertThrows(ApplicationException.class, () -> service.request(EDITOR, ids.getFirst()))
              .code());
      assertEquals(
          "not_found",
          assertThrows(ApplicationException.class, () -> service.status(EDITOR, ids.getFirst()))
              .code());
      assertEquals(3, scalar(store, "SELECT COUNT(*) FROM document_cleanups"));
    }
  }

  @ParameterizedTest
  @CsvSource({"0,20", "1,0", "1,101"})
  void invalidPaginationDoesNotFallBackToAnUnboundedCleanupList(int page, int size) {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = localService(authority.store());
      String id = parsed(authority, "pagination material");
      service.request(OWNER, id);
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> service.list(OWNER, page, size)).code());
      assertEquals(1, service.list(OWNER, 1, 100).total());
    }
  }

  @Test
  void missingActorCannotReadControlOrBatchEvenWhenDocumentIdentifiersAreValid() {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = localService(authority.store());
      String id = parsed(authority, "unauthenticated control material");
      assertThrows(ApplicationException.class, () -> service.request(null, id));
      assertThrows(ApplicationException.class, () -> service.status(null, id));
      assertThrows(ApplicationException.class, () -> service.list(null, 1, 20));
      assertThrows(ApplicationException.class, () -> service.batch(null, List.of(id)));
      assertEquals(0, scalar(authority.store(), "SELECT COUNT(*) FROM document_tombstones"));
    }
  }

  @Test
  void unfinishedClaimCanResumeWithANewTokenButTheOldTokenCannotSealOrChangeResources() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var repository = new DocumentCleanupRepository(store);
      String id = parsed(authority, "resumable claim material");
      localService(store).request(OWNER, id);
      var first = store.transaction(() -> repository.claimNext(NOW).orElseThrow());
      var pending = store.transaction(() -> repository.finish(first, NOW));
      assertEquals("pending", pending.cleanupStatus());
      assertNull(pending.completedAt());
      var second = store.transaction(() -> repository.claimNext(NOW).orElseThrow());
      assertNotEquals(first.claimToken(), second.claimToken());
      assertFalse(store.transaction(() -> repository.current(first)));
      assertFalse(store.transaction(() -> repository.current(null)));
      assertTrue(store.transaction(() -> repository.current(second)));
      assertThrows(
          ApplicationException.class, () -> store.transaction(() -> repository.sealPlan(first)));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.setResource(first, "database_payload", "completed", null, NOW);
                    return null;
                  }));
      assertThrows(
          ApplicationException.class, () -> store.transaction(() -> repository.finish(first, NOW)));
      var plan = store.transaction(() -> repository.sealPlan(second));
      assertEquals(plan, store.transaction(() -> repository.sealPlan(second)));
      assertTrue(
          scalar(
                  store,
                  "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?",
                  id)
              > 0);
    }
  }

  @ParameterizedTest
  @CsvSource({"blocked,NULL", "failed,NULL", "failed,Internal detail: payload"})
  void invalidResourceFailuresAreRejectedWithoutPartialLedgerUpdates(String status, String code) {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var repository = new DocumentCleanupRepository(store);
      String id = parsed(authority, "resource receipt material");
      var service = localService(store);
      service.request(OWNER, id);
      var claim = store.transaction(() -> repository.claimNext(NOW).orElseThrow());
      String safeCode = code.equals("NULL") ? null : code;
      var before = service.status(OWNER, id);
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.setResource(claim, "remote_logical_rows", status, safeCode, NOW);
                    return null;
                  }));
      assertEquals(before, service.status(OWNER, id));
      assertTrue(store.transaction(() -> repository.current(claim)));
    }
  }

  @Test
  void completedResourceClaimsWithoutASealedPurgeCannotTurnADocumentIntoDeleted() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var repository = new DocumentCleanupRepository(store);
      String id = parsed(authority, "cannot complete without real purge");
      var service = localService(store);
      service.request(OWNER, id);
      var claim = store.transaction(() -> repository.claimNext(NOW).orElseThrow());
      store.transaction(
          () -> {
            for (String kind : CleanupResource.KINDS) {
              repository.setResource(claim, kind, "completed", null, NOW);
            }
            return null;
          });
      assertThrows(
          ApplicationException.class, () -> store.transaction(() -> repository.finish(claim, NOW)));
      assertEquals("running", service.status(OWNER, id).cleanupStatus());
      assertEquals("deleting", service.status(OWNER, id).status());
      assertTrue(
          scalar(
                  store,
                  "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?",
                  id)
              > 0);
    }
  }

  @Test
  void attemptReplayCannotRewriteATargetAndWriteIntentCannotBeInventedAfterTheFact() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var repository = new DocumentCleanupRepository(store);
      String id = parsed(authority, "qualified attempt material");
      var attempt = attempt(store, id, "generation-one", "first");
      store.transaction(
          () -> {
            repository.registerProjectionAttempt(attempt);
            repository.registerProjectionAttempt(attempt);
            return null;
          });
      var changedTarget = attempt(store, id, "generation-one", "other");
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.registerProjectionAttempt(changedTarget);
                    return null;
                  }));
      var issued =
          new ProjectionAttempt(
              attempt.documentId(),
              attempt.workspaceId(),
              attempt.sourceRevisionId(),
              attempt.sourceSha256(),
              "never-recorded",
              attempt.route(),
              attempt.target(),
              true);
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.registerProjectionAttempt(issued);
                    return null;
                  }));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.markProjectionWriteIssued("never-recorded", "legacy");
                    return null;
                  }));
      localService(store).request(OWNER, id);
      var claim = store.transaction(() -> repository.claimNext(NOW).orElseThrow());
      var unissued = store.transaction(() -> repository.projectionInventory(claim));
      assertTrue(unissued.known());
      assertEquals(List.of(attempt), unissued.attempts());
      assertFalse(unissued.attempts().getFirst().writeIssued());
      store.transaction(
          () -> {
            repository.markProjectionWriteIssued(attempt.generationId(), attempt.route());
            repository.markProjectionWriteIssued(attempt.generationId(), attempt.route());
            return null;
          });
      assertTrue(
          store
              .transaction(() -> repository.projectionInventory(claim))
              .attempts()
              .getFirst()
              .writeIssued());
      assertEquals(1, scalar(store, "SELECT COUNT(*) FROM cleanup_projection_attempts"));
    }
  }

  @Test
  void registeredTargetGroupsKeepFailureAndBlockedPhysicalStatusEvenAfterLaterSuccess() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String id = parsed(authority, "grouped remote generation material");
      var repository = new DocumentCleanupRepository(store);
      for (String name : List.of("a", "b", "c")) {
        var value = attempt(store, id, "generation-" + name, name);
        store.transaction(
            () -> {
              repository.registerProjectionAttempt(value);
              return null;
            });
      }
      var visited = new ArrayList<String>();
      var service =
          service(
              store,
              attempts -> {
                assertEquals(1, attempts.size());
                String generation = attempts.getFirst().generationId();
                visited.add(generation);
                return switch (generation) {
                  case "generation-a" ->
                      new ProjectionCleanup.Result("blocked", "completed", "blocked", null);
                  case "generation-b" ->
                      new ProjectionCleanup.Result("failed", "failed", "completed", null);
                  case "generation-c" ->
                      new ProjectionCleanup.Result("completed", "completed", "completed", null);
                  default -> throw new AssertionError(generation);
                };
              });
      service.request(OWNER, id);
      assertTrue(service.runOnce());
      var receipt = service.status(OWNER, id);
      assertEquals(List.of("generation-a", "generation-b", "generation-c"), visited);
      assertEquals("failed", receipt.cleanupStatus());
      assertEquals("deleting", receipt.status());
      assertEquals("cleanup_remote_unverified", receipt.errorCode());
      assertEquals("failed", resource(receipt, "remote_logical_rows"));
      assertEquals("failed", resource(receipt, "remote_write_terminal"));
      assertEquals("blocked", resource(receipt, "remote_physical_storage"));
      assertEquals("completed", resource(receipt, "database_payload"));
      assertEquals(receipt, service.request(OWNER, id));
      assertFalse(service.runOnce());
      assertEquals(3, visited.size());
    }
  }

  @Test
  void remoteExceptionIsSanitizedAndDoesNotRollBackRealLocalPurgeOrReportCompletion() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String id = parsed(authority, "provider exception source");
      var registered = attempt(store, id, "throwing-generation", "throwing");
      store.transaction(
          () -> {
            new DocumentCleanupRepository(store).registerProjectionAttempt(registered);
            return null;
          });
      var calls = new AtomicInteger();
      var service =
          service(
              store,
              attempts -> {
                calls.incrementAndGet();
                throw new IllegalStateException("synthetic private provider response");
              });
      service.request(OWNER, id);
      assertTrue(service.runOnce());
      var receipt = service.status(OWNER, id);
      assertEquals("failed", receipt.cleanupStatus());
      assertEquals("cleanup_execution_failed", receipt.errorCode());
      assertEquals("failed", resource(receipt, "remote_logical_rows"));
      assertEquals("pending", resource(receipt, "remote_physical_storage"));
      assertEquals("completed", resource(receipt, "database_payload"));
      assertEquals(
          0,
          scalar(
              store, "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?", id));
      assertFalse(receipt.toString().contains("private provider"));
      assertFalse(service.runOnce());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void migratedUnknownProjectionHistoryCannotBeInferredFromAnEmptyPublicationTable()
      throws Exception {
    String id;
    try (var authority = new AuthorityTestContext(directory)) {
      // Seed the historical v21 workflow, without creating a newer v34 admission intent.
      authority.ingestion().setAutomaticIndexingEnabled(false);
      id = parsed(authority, "legacy projection provenance");
    }
    CleanupV21Fixture.restoreVersionTwentyOne(directory);
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals(
          0,
          scalar(
              store,
              "SELECT inventory_known FROM cleanup_document_provenance WHERE document_id=?",
              id));
      var service = localService(store);
      service.request(OWNER, id);
      assertTrue(service.runOnce());
      var receipt = service.status(OWNER, id);
      assertEquals("blocked", receipt.cleanupStatus());
      assertEquals("cleanup_inventory_unknown", receipt.errorCode());
      for (String kind :
          List.of(
              "remote_inventory",
              "remote_logical_rows",
              "remote_write_terminal",
              "remote_physical_storage")) {
        assertEquals("blocked", resource(receipt, kind));
      }
      assertEquals("completed", resource(receipt, "database_payload"));
      assertEquals("completed", resource(receipt, "managed_backups"));
      assertEquals(
          0,
          scalar(
              store, "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?", id));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"size", "hash", "directory", "symlink"})
  void changedRegisteredBackupIsPreservedAndItsFailedResourcePreventsCompletion(String changed)
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String id = parsed(authority, "registered backup boundary");
      byte[] saved = "registered synthetic file".getBytes(StandardCharsets.UTF_8);
      Path backup =
          store.libraryPath().getParent().resolve("java-library.v21-before-v22-boundary.db");
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO cleanup_managed_backups(relative_path,sha256,size_bytes) VALUES(?,?,?)",
                backup.getFileName().toString(),
                ModelValues.sha256(saved),
                saved.length);
            return null;
          });
      Path external = directory.resolve("external-untouched");
      switch (changed) {
        case "size" -> Files.write(backup, new byte[] {1});
        case "hash" -> {
          var sameLength = saved.clone();
          sameLength[0] = 0;
          Files.write(backup, sameLength);
        }
        case "directory" -> Files.createDirectory(backup);
        case "symlink" -> {
          Files.write(external, saved);
          Files.createSymbolicLink(backup, external.toAbsolutePath());
        }
        default -> throw new AssertionError(changed);
      }
      byte[] before = changed.equals("directory") ? null : Files.readAllBytes(backup);
      var service = localService(store);
      service.request(OWNER, id);
      assertTrue(service.runOnce());
      var receipt = service.status(OWNER, id);
      assertEquals("failed", receipt.cleanupStatus());
      assertEquals("cleanup_execution_failed", receipt.errorCode());
      assertEquals("failed", resource(receipt, "managed_backups"));
      assertEquals("completed", resource(receipt, "database_payload"));
      if (changed.equals("directory")) {
        assertTrue(Files.isDirectory(backup));
      } else {
        assertArrayEquals(before, Files.readAllBytes(backup));
      }
      if (changed.equals("symlink")) {
        assertTrue(Files.isSymbolicLink(backup));
        assertArrayEquals(saved, Files.readAllBytes(external));
      }
    }
  }

  @Test
  void unregisteredCrashStagingIsNotDeletedOrAssumedToBeAnAbsentBackup() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String id = parsed(authority, "unknown backup boundary");
      Path unknown =
          store.libraryPath().getParent().resolve(".cleanup-backup-unregistered.partial");
      Files.writeString(unknown, "unknown staging payload");
      var service = localService(store);
      service.request(OWNER, id);
      assertTrue(service.runOnce());
      var receipt = service.status(OWNER, id);
      assertEquals("blocked", receipt.cleanupStatus());
      assertEquals("cleanup_backups_unknown", receipt.errorCode());
      assertEquals("blocked", resource(receipt, "managed_backups"));
      assertEquals("completed", resource(receipt, "database_payload"));
      assertEquals("unknown staging payload", Files.readString(unknown));
    }
  }

  private static String parsed(AuthorityTestContext authority, String text) {
    byte[] content = text.getBytes(StandardCharsets.UTF_8);
    var task = authority.ingestion().uploadDocument(OWNER, "fixture.txt", "text/plain", content);
    var claim = authority.ingestion().claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(
                claim, new TextParser().parse("fixture.txt", "text/plain", content)));
    return task.documentId();
  }

  private static ProjectionAttempt attempt(
      SqliteAuthorityStore store, String id, String generation, String collection) {
    var row =
        store.transaction(
            () ->
                new DocumentLifecycleRepository(store)
                    .findWritableDocument(OWNER, id)
                    .orElseThrow());
    return new ProjectionAttempt(
        id,
        OWNER.workspaceId(),
        row.registrationRevisionId(),
        row.sourceSha256(),
        generation,
        "legacy",
        new QualifiedProjectionTarget(
            "http://127.0.0.1:1",
            "default",
            "java_" + collection,
            OWNER.workspaceId(),
            "synthetic-embedding-v1",
            2,
            ModelValues.sha256(collection.getBytes(StandardCharsets.UTF_8))),
        false);
  }

  private static long scalar(SqliteAuthorityStore store, String sql, Object... parameters) {
    return store.transaction(() -> store.count(sql, parameters));
  }

  private static String resource(DocumentCleanupState receipt, String kind) {
    return receipt.resources().stream()
        .filter(resource -> resource.kind().equals(kind))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static DocumentCleanupService localService(SqliteAuthorityStore store) {
    return service(
        store,
        attempts -> {
          throw new AssertionError("Unregistered or absent remote history must not dispatch");
        });
  }

  private static DocumentCleanupService service(
      SqliteAuthorityStore store, ProjectionCleanup projection) {
    return new DocumentCleanupService(
        store,
        new DocumentCleanupRepository(store),
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        projection);
  }
}
