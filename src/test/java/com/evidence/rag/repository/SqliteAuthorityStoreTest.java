package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared authority invariants that the former one-class monitor did not need to expose separately.
 */
class SqliteAuthorityStoreTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void repositoriesRejectAccessOutsideTheOwningThreadTransaction() {
    try (var store = new SqliteAuthorityStore(directory);
        var executor = Executors.newSingleThreadExecutor()) {
      var repository = new ManagementRepository(store);
      assertThrows(IllegalStateException.class, () -> repository.findTags(owner));
      store.transaction(
          () -> {
            assertEquals(List.of(), repository.findTags(owner));
            var future = executor.submit(() -> repository.findTags(owner));
            var failure =
                assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertThrows(IllegalStateException.class, () -> store.transaction(() -> null));
            assertEquals(List.of(), repository.findTags(owner));
            return null;
          });
      assertThrows(
          IllegalStateException.class, () -> repository.insertGrant("missing", "owner", "owner"));
    }
  }

  @Test
  void oneRollbackRemovesChangesAcrossRepositoriesAndAuditAndSurvivesReopen() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var management = new ManagementRepository(store);
      var ingestion = new IngestionRepository(store);
      var failure =
          assertThrows(
              ApplicationException.class,
              () ->
                  store.transaction(
                      () -> {
                        management.insertDocument(
                            owner,
                            new SyntheticDocument(
                                "document",
                                "sample.txt",
                                "document",
                                "text/plain",
                                "revision",
                                "a".repeat(64),
                                1),
                            "2026-09-07T00:00:00Z");
                        management.insertGrant("document", "owner", "owner");
                        ingestion.insertOriginal(
                            "document",
                            "revision",
                            "parser-v1",
                            "a".repeat(64),
                            new byte[] {65},
                            "now");
                        ingestion.insertJob("job", "document", "revision", "owner", "now");
                        management.insertAudit(
                            AuditEventEntity.create(
                                owner,
                                "document",
                                "ingestion_queued",
                                null,
                                Map.of("state", "queued"),
                                Set.of("state")));
                        throw new ApplicationException(
                            FailureKind.CONFLICT, "rollback_probe", "synthetic rollback");
                      }));
      assertEquals("rollback_probe", failure.code());
      assertEmptyAuthority(store);
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      assertEmptyAuthority(reopened);
    }
  }

  private void assertEmptyAuthority(SqliteAuthorityStore store) {
    store.transaction(
        () -> {
          for (String table :
              List.of(
                  "documents",
                  "document_acl",
                  "corpus_documents",
                  "corpus_revisions",
                  "ingestion_jobs",
                  "management_audit")) {
            assertEquals(0L, store.count("SELECT COUNT(*) FROM " + table));
          }
          return null;
        });
  }

  @Test
  void differentServicesSerializeOnTheSameAuthorityWhileKeepingBothUseCases() throws Exception {
    try (var context = new AuthorityTestContext(directory);
        var executor = Executors.newFixedThreadPool(6)) {
      context
          .management()
          .registerSyntheticDocument(
              owner,
              new SyntheticDocument(
                  "existing",
                  "original.txt",
                  "document",
                  "text/plain",
                  "registration",
                  "a".repeat(64),
                  1),
              Map.of());
      var work = new ArrayList<Callable<Void>>();
      for (int index = 0; index < 12; index++) {
        final int item = index;
        work.add(
            () -> {
              var task =
                  context
                      .ingestion()
                      .uploadDocument(
                          owner, "upload-" + item + ".txt", "text/plain", new byte[] {65});
              assertEquals("queued", task.state());
              return null;
            });
        work.add(
            () -> {
              var document =
                  context
                      .management()
                      .updateDocument(
                          owner,
                          "existing",
                          new DocumentPatchCommand(
                              true, "Title " + item, false, null, true, List.of("tag-" + item)));
              assertEquals("original.txt", document.filename());
              assertEquals("Title " + item, document.displayName());
              assertEquals(List.of("tag-" + item), document.tags());
              return null;
            });
      }
      for (var future : executor.invokeAll(work, 15, TimeUnit.SECONDS)) {
        future.get(10, TimeUnit.SECONDS);
      }
      var page =
          context
              .management()
              .listDocuments(
                  owner, new DocumentQuery("", null, null, null, null, "updated_desc", 1, 100));
      assertEquals(13L, page.total());
      assertEquals(24, context.management().auditEvents(owner).size());
    }
  }

  @Test
  void reopeningStorageDoesNotRunBusinessRecoveryUntilStartupInvokesService() {
    String jobId;
    try (var context = new AuthorityTestContext(directory)) {
      context.ingestion().uploadDocument(owner, "original.txt", "text/plain", new byte[] {65});
      jobId = context.ingestion().claimIngestion(owner.workspaceId()).orElseThrow().jobId();
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var ingestionRepository = new IngestionRepository(store);
      var managementRepository = new ManagementRepository(store);
      assertEquals(
          "processing",
          store.transaction(
              () -> ingestionRepository.findInternalTask(jobId).orElseThrow().state()));
      var service =
          new IngestionService(
              store, ingestionRepository, managementRepository, new DocumentPermissionPolicy());
      service.recoverIngestions();
      assertEquals("failed", service.ingestionStatus(owner, jobId).state());
      assertEquals("worker_interrupted", service.ingestionStatus(owner, jobId).errorCode());
      var auditCount =
          store.transaction(
              () -> managementRepository.findAudit(new Actor("org", "system:ingestion")).size());
      service.recoverIngestions();
      assertEquals(
          auditCount,
          store.transaction(
              () -> managementRepository.findAudit(new Actor("org", "system:ingestion")).size()));
      assertTrue(store.transaction(() -> ingestionRepository.processingIds().isEmpty()));
    }
  }
}
