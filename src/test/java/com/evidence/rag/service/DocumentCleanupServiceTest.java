package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.OwnedTemporaryResources;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentCleanupServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void localPayloadReallyDisappearsWhileIdentityOtherDocumentsAndCompletionSurviveRestart()
      throws Exception {
    String removed;
    String retained;
    String cleanupId;
    String syntheticPayload = "UNIQUE_SYNTHETIC_PURGE_PAYLOAD_ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    try (var authority = new AuthorityTestContext(directory)) {
      removed = parsed(authority, syntheticPayload);
      retained = parsed(authority, "retained unrelated synthetic material");
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError("Pure local cleanup must never call a provider");
              });
      long resident =
          authority
              .store()
              .transaction(
                  () ->
                      new IngestionRepository(authority.store()).storedBytes(owner.workspaceId()));
      var accepted = service.request(owner, removed);
      cleanupId = accepted.cleanupId();
      assertEquals("pending", accepted.cleanupStatus());
      assertEquals(9, accepted.resources().size());
      assertEquals(
          resident,
          authority
              .store()
              .transaction(
                  () ->
                      new IngestionRepository(authority.store()).storedBytes(owner.workspaceId())));
      assertEquals(cleanupId, service.request(owner, removed).cleanupId());

      assertTrue(service.runOnce());
      var completed = service.status(owner, removed);
      assertEquals("deleted", completed.status());
      assertEquals("completed", completed.cleanupStatus());
      assertTrue(
          completed.resources().stream()
              .allMatch(r -> List.of("completed", "not_applicable").contains(r.status())));
      assertNull(completed.errorCode());
      assertTrue(Files.isRegularFile(directory.resolve(".cleanup-restore-high-water.jsonl")));
      assertEquals(
          1,
          scalar(
              "SELECT count(*) FROM management_audit WHERE entity_id=? AND action='document_cleanup_completed'",
              removed));
      assertEquals(cleanupId, service.request(owner, removed).cleanupId());
      assertFalse(service.runOnce());
      assertEquals(
          0,
          scalar(
              "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?", removed));
      assertEquals(
          1, scalar("SELECT payload_purged FROM corpus_documents WHERE document_id=?", removed));
      assertEquals(
          0,
          scalar(
              "SELECT sum(length(text)) FROM corpus_pages WHERE revision_id=(SELECT initial_revision_id FROM corpus_documents WHERE document_id=?)",
              removed));
      assertEquals(
          1,
          scalar(
              "SELECT count(*) FROM documents WHERE id=? AND source_sha256='"
                  + ModelValues.sha256(syntheticPayload.getBytes(StandardCharsets.UTF_8))
                  + "'",
              removed));
      assertTrue(
          scalar("SELECT length(original_blob) FROM corpus_documents WHERE document_id=?", retained)
              > 0);
      assertFalse(
          new String(
                  Files.readAllBytes(authority.store().libraryPath()), StandardCharsets.ISO_8859_1)
              .contains(syntheticPayload));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      var result =
          service(
                  reopened,
                  attempts -> {
                    throw new AssertionError("No remote attempt exists");
                  })
              .status(owner, removed);
      assertEquals("completed", result.cleanupStatus());
      assertEquals(cleanupId, result.cleanupId());
      assertTrue(
          reopened.transaction(() -> new IngestionRepository(reopened).original(retained)).length
              > 0);
    }
  }

  @Test
  void currentAclPrecedesBusyAndNoRunningBodyCanBeWithdrawnOrPurged() {
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority, "active synthetic document");
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError();
              });
      try (var lease = authority.store().operationGate().enter()) {
        assertEquals(
            "document_busy",
            assertThrows(ApplicationException.class, () -> service.request(owner, id)).code());
        assertEquals(
            FailureKind.NOT_FOUND,
            assertThrows(
                    ApplicationException.class,
                    () -> service.request(new Actor("org-main", "reader"), id))
                .kind());
        assertEquals(
            "not_found",
            assertThrows(ApplicationException.class, () -> service.request(owner, "missing"))
                .code());
        assertFalse(service.runOnce());
        var batch = service.batch(owner, List.of(id, "missing"));
        assertEquals(
            List.of("busy", "not_found"),
            batch.items().stream().map(item -> item.status()).toList());
      }
      assertEquals(0, scalar("SELECT count(*) FROM document_tombstones WHERE document_id=?", id));
      assertEquals("pending", service.request(owner, id).cleanupStatus());
    }
  }

  @Test
  void anExistingReceiptReplaysDuringMaintenanceOrAnActiveBodyWithoutRetrying() {
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority, "stable cleanup receipt");
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError();
              });
      var accepted = service.request(owner, id);
      try (var body = authority.store().operationGate().enter()) {
        assertEquals(accepted, service.request(owner, id));
        assertEquals("accepted", service.batch(owner, List.of(id)).items().getFirst().status());
        assertEquals(
            "not_found",
            assertThrows(
                    ApplicationException.class,
                    () -> service.request(new Actor("org-main", "reader"), id))
                .code());
      }
      try (var maintenance = authority.store().operationGate().tryMaintenance().orElseThrow()) {
        authority
            .store()
            .transaction(
                () ->
                    new DocumentCleanupRepository(authority.store())
                        .claimNext(Instant.now().toString())
                        .orElseThrow());
        var running = service.request(owner, id);
        assertEquals("running", running.cleanupStatus());
        assertEquals(accepted.cleanupId(), running.cleanupId());
      }
      assertEquals(1, scalar("SELECT count(*) FROM document_cleanups WHERE document_id=?", id));
      assertEquals(
          1,
          scalar(
              "SELECT count(*) FROM management_audit WHERE entity_id=? AND action='document_removal_requested'",
              id));
    }
  }

  @Test
  void queuedTaskIsBusyAndOldWithdrawalIsOnlyTakenOverByAnExplicitRequest() {
    try (var authority = new AuthorityTestContext(directory)) {
      var queued =
          authority
              .ingestion()
              .uploadDocument(
                  owner, "queued.txt", "text/plain", "queued".getBytes(StandardCharsets.UTF_8));
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError();
              });
      assertEquals(
          "document_busy",
          assertThrows(
                  ApplicationException.class, () -> service.request(owner, queued.documentId()))
              .code());
      var lifecycle =
          new DocumentLifecycleService(
              authority.store(),
              new DocumentLifecycleRepository(authority.store()),
              new ManagementRepository(authority.store()),
              new IngestionRepository(authority.store()),
              new IndexingRepository(authority.store()),
              new DocumentPermissionPolicy());
      lifecycle.removeDocument(owner, queued.documentId());
      assertEquals("not_requested", service.status(owner, queued.documentId()).cleanupStatus());
      assertFalse(service.runOnce());
      assertEquals("pending", service.request(owner, queued.documentId()).cleanupStatus());
      assertTrue(service.runOnce());
      assertEquals("completed", service.status(owner, queued.documentId()).cleanupStatus());
    }
  }

  @Test
  void registeredFailedGenerationsAreVisitedAndLogicalAbsenceIsNotPhysicalCompletion() {
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority, "remote synthetic source");
      var row =
          authority
              .store()
              .transaction(
                  () ->
                      new DocumentLifecycleRepository(authority.store())
                          .findWritableDocument(owner, id)
                          .orElseThrow());
      var settings =
          new MilvusRestProjection.Settings(
              URI.create("http://127.0.0.1:1"),
              "",
              "default",
              "java_cleanup_test",
              "org-main",
              "fixture/embed@v1",
              2,
              Duration.ofSeconds(1),
              1048576,
              true);
      var registry = new DocumentCleanupRepository(authority.store());
      authority
          .store()
          .transaction(
              () -> {
                for (String generation : List.of("failed-generation", "published-generation")) {
                  registry.registerProjectionAttempt(
                      new ProjectionAttempt(
                          id,
                          owner.workspaceId(),
                          row.registrationRevisionId(),
                          row.sourceSha256(),
                          generation,
                          "legacy",
                          MilvusProjectionCleanup.qualified(settings),
                          false));
                  registry.markProjectionWriteIssued(generation, "legacy");
                }
                return null;
              });
      var calls = new AtomicInteger();
      var service =
          service(
              authority.store(),
              attempts -> {
                assertEquals(
                    List.of("failed-generation", "published-generation"),
                    attempts.stream().map(ProjectionAttempt::generationId).toList());
                assertTrue(attempts.stream().allMatch(ProjectionAttempt::writeIssued));
                calls.incrementAndGet();
                return new ProjectionCleanup.Result(
                    "completed", "blocked", "blocked", "cleanup_remote_unverified");
              });
      service.request(owner, id);
      assertTrue(service.runOnce());
      var state = service.status(owner, id);
      assertEquals("blocked", state.cleanupStatus());
      assertEquals("deleting", state.status());
      assertEquals(
          "completed",
          state.resources().stream()
              .filter(r -> r.kind().equals("database_payload"))
              .findFirst()
              .orElseThrow()
              .status());
      assertEquals(
          "blocked",
          state.resources().stream()
              .filter(r -> r.kind().equals("remote_physical_storage"))
              .findFirst()
              .orElseThrow()
              .status());
      assertEquals(1, calls.get());
      assertFalse(service.runOnce());
      assertEquals(state.cleanupId(), service.request(owner, id).cleanupId());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void ownedPayloadCanBeSweptButUnknownTemporaryBlocksOnlyItsOwnResource() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority, "temporary cleanup synthetic source");
      Path work;
      try (var lease = authority.store().operationGate().enter()) {
        work = OwnedTemporaryResources.createDirectory("rag-index-");
      }
      Files.writeString(work.resolve("source"), "owned private payload");
      Path unknown = authority.store().operationGate().managedRoot().resolve("unknown");
      Files.writeString(unknown, "foreign payload");
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError();
              });
      service.request(owner, id);
      service.runOnce();
      var state = service.status(owner, id);
      assertEquals("blocked", state.cleanupStatus());
      assertEquals(
          "pending",
          state.resources().stream()
              .filter(r -> r.kind().equals("database_payload"))
              .findFirst()
              .orElseThrow()
              .status());
      assertEquals(
          "blocked",
          state.resources().stream()
              .filter(r -> r.kind().equals("managed_temporaries"))
              .findFirst()
              .orElseThrow()
              .status());
      assertTrue(
          scalar("SELECT length(original_blob) FROM corpus_documents WHERE document_id=?", id) > 0);
      assertEquals("foreign payload", Files.readString(unknown));
      assertTrue(Files.exists(work.resolve("source")));
    }
  }

  @Test
  void restoredPreCleanupDatabaseCannotResurrectPayloadBehindTheFsyncedBarrier() throws Exception {
    byte[] old;
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority, "restore barrier synthetic source");
      Path snapshot = directory.resolve("controlled-old-copy.db");
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + authority.store().libraryPath());
          var statement = connection.prepareStatement("VACUUM INTO ?")) {
        statement.setString(1, snapshot.toString());
        statement.execute();
      }
      old = Files.readAllBytes(snapshot);
      Files.delete(snapshot);
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError();
              });
      service.request(owner, id);
      service.runOnce();
      assertEquals("completed", service.status(owner, id).cleanupStatus());
    }
    Files.write(directory.resolve("java-library.db"), old);
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @Test
  void registeredManagedBackupIsActuallyRewrittenAndUnrelatedMaterialRemains() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority, "PRIVATE_SYNTHETIC_MANAGED_BACKUP_PAYLOAD");
      String other = parsed(authority, "other backup material");
      Path snapshot = directory.resolve("java-library.v21-before-v22-fixture.db");
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + authority.store().libraryPath());
          var statement = connection.prepareStatement("VACUUM INTO ?")) {
        statement.setString(1, snapshot.toString());
        statement.execute();
      }
      String oldSha = ModelValues.sha256(Files.readAllBytes(snapshot));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + authority.store().libraryPath());
          var statement =
              connection.prepareStatement(
                  "INSERT INTO cleanup_managed_backups(relative_path,sha256,size_bytes) VALUES(?,?,?)")) {
        statement.setString(1, snapshot.getFileName().toString());
        statement.setString(2, oldSha);
        statement.setLong(3, Files.size(snapshot));
        statement.execute();
      }
      var service =
          service(
              authority.store(),
              attempts -> {
                throw new AssertionError();
              });
      service.request(owner, id);
      service.runOnce();
      assertEquals("completed", service.status(owner, id).cleanupStatus());
      assertNotEquals(oldSha, ModelValues.sha256(Files.readAllBytes(snapshot)));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
          var statement =
              connection.prepareStatement(
                  "SELECT document_id,length(original_blob) FROM corpus_documents ORDER BY document_id")) {
        var rows = statement.executeQuery();
        int count = 0;
        while (rows.next()) {
          assertEquals(
              rows.getString(1).equals(id) ? 0 : "other backup material".length(), rows.getInt(2));
          count++;
        }
        assertEquals(2, count);
      }
      assertTrue(
          scalar("SELECT length(original_blob) FROM corpus_documents WHERE document_id=?", other)
              > 0);
    }
  }

  private String parsed(AuthorityTestContext authority, String text) {
    byte[] content = text.getBytes(StandardCharsets.UTF_8);
    var task = authority.ingestion().uploadDocument(owner, "fixture.txt", "text/plain", content);
    var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(
                claim, new TextParser().parse("fixture.txt", "text/plain", content)));
    return task.documentId();
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

  private long scalar(String sql, String id) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      var result = statement.executeQuery();
      assertTrue(result.next());
      return result.getLong(1);
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }
}
