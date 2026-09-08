package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Current creator authorization is observed through the real authority interfaces. */
class IngestionAuthorizationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");
  private final Actor editor = new Actor("org-main", "editor");
  private final Actor system = new Actor("org-main", "system:ingestion");

  @Test
  void queuedRevocationsAreSkippedBeforeAStillAuthorizedCreatorIsClaimed() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var removed = upload(authority, owner);
      var downgraded = upload(authority, owner);
      revoke(removed.documentId(), owner.principalId());
      role(downgraded.documentId(), owner.principalId(), "reader");
      var allowed = upload(authority, owner);
      role(allowed.documentId(), owner.principalId(), "editor");
      var other = upload(authority, new Actor("other", owner.principalId()));

      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();

      assertEquals(allowed.taskId(), claim.jobId());
      assertCancelled(authority, removed, 1);
      assertCancelled(authority, downgraded, 1);
      assertTrue(authority.ingestion().isIngestionClaimCurrent(claim));
      assertTrue(authority.ingestion().completeIngestion(claim, parsed()));
      assertEquals(
          "queued",
          authority
              .ingestion()
              .ingestionStatus(new Actor("other", "owner"), other.taskId())
              .state());
      assertTrue(authority.ingestion().claimIngestion(owner.workspaceId()).isEmpty());
    }
  }

  @Test
  void aFullyRevokedQueueIsExhaustedWithinItsBoundAndReleasesAdmissionQuota() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var tasks = new ArrayList<TaskResult>();
      for (int index = 0; index < 32; index++) {
        var task = upload(authority, owner);
        tasks.add(task);
        revoke(task.documentId(), owner.principalId());
      }

      assertTimeout(
          Duration.ofSeconds(5),
          () -> assertTrue(authority.ingestion().claimIngestion(owner.workspaceId()).isEmpty()));

      for (var task : tasks) {
        assertCancelled(authority, task, 1);
      }
      assertTrue(authority.ingestion().claimIngestion(owner.workspaceId()).isEmpty());
      assertEquals(32, cancellationCount(authority));
      var next = upload(authority, owner);
      assertEquals(
          next.taskId(),
          authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow().jobId());
    }
  }

  @Test
  void currentClaimRevocationCommitsOneCancellationAndLateCallbacksCannotOverwriteIt()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(authority.ingestion().isIngestionClaimCurrent(claim));
      role(task.documentId(), owner.principalId(), "reader");

      assertFalse(authority.ingestion().isIngestionClaimCurrent(claim));

      assertCancelled(authority, task, 1);
      assertFalse(authority.ingestion().isIngestionClaimCurrent(claim));
      assertFalse(authority.ingestion().completeIngestion(claim, parsed()));
      assertFalse(authority.ingestion().failIngestion(claim, "parser_failed"));
      assertEquals(1, cancellationCount(authority));
      assertSensitiveValuesAbsentFromAudit(authority, claim);
    }
  }

  @Test
  void directCompletionAfterRevocationCancelsBeforeAnyEvidenceIsPersisted() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      revoke(task.documentId(), owner.principalId());

      assertFalse(authority.ingestion().completeIngestion(claim, parsed()));

      assertCancelled(authority, task, 1);
      assertArrayEquals(
          claim.content(),
          authority
              .store()
              .transaction(
                  () -> new IngestionRepository(authority.store()).original(task.documentId())));
      var document = documentTask(authority, editor, task.documentId());
      assertEquals(task.revisionId(), document.revisionId());
      assertFalse(document.canRetry());
    }
  }

  @Test
  void directFailureAfterRevocationCancelsInsteadOfRecordingAParserFailure() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      revoke(task.documentId(), owner.principalId());

      assertFalse(authority.ingestion().failIngestion(claim, "parser_timeout"));

      assertCancelled(authority, task, 1);
      assertFalse(authority.ingestion().failIngestion(claim, "parser_failed"));
      assertEquals(1, cancellationCount(authority));
    }
  }

  @Test
  void forgedClaimIdentityCannotCancelTheCurrentTaskEvenWhenItsCreatorWasRevoked()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      revoke(task.documentId(), owner.principalId());
      for (String field :
          List.of(
              "job",
              "document",
              "revision",
              "workspace",
              "attempt",
              "token",
              "filename",
              "mime",
              "parser")) {
        var forged =
            new IngestionClaim(
                field.equals("job") ? "missing" : claim.jobId(),
                field.equals("document") ? "other" : claim.documentId(),
                field.equals("revision") ? "other" : claim.revisionId(),
                field.equals("workspace") ? "other" : claim.workspaceId(),
                field.equals("attempt") ? 2 : claim.attempt(),
                field.equals("token") ? "x".repeat(72) : claim.token(),
                field.equals("filename") ? "other.txt" : claim.filename(),
                field.equals("mime") ? "application/pdf" : claim.mimeType(),
                field.equals("parser") ? "other" : claim.parserRevision(),
                claim.content());
        assertFalse(authority.ingestion().isIngestionClaimCurrent(forged), field);
        assertFalse(authority.ingestion().completeIngestion(forged, parsed()), field);
        assertFalse(authority.ingestion().failIngestion(forged, "parser_failed"), field);
      }
      assertFalse(authority.ingestion().isIngestionClaimCurrent(null));
      assertEquals(
          "processing", authority.ingestion().ingestionStatus(editor, task.taskId()).state());
      assertEquals(0, cancellationCount(authority));
      assertEquals(
          1L,
          scalar(
              "SELECT COUNT(*) FROM ingestion_jobs WHERE id=? AND claim_token_sha256 IS NOT NULL",
              task.taskId()));

      assertFalse(authority.ingestion().isIngestionClaimCurrent(claim));
      assertCancelled(authority, task, 1);
    }
  }

  @Test
  void invalidCompletionPayloadPreservesItsErrorBeforeRevocationCanCancelTheTask()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      revoke(task.documentId(), owner.principalId());
      var changed =
          new IngestionClaim(
              claim.jobId(),
              claim.documentId(),
              claim.revisionId(),
              claim.workspaceId(),
              claim.attempt(),
              claim.token(),
              claim.filename(),
              claim.mimeType(),
              claim.parserRevision(),
              new byte[] {1});

      var problem =
          assertThrows(
              ApplicationException.class,
              () -> authority.ingestion().completeIngestion(changed, parsed()));

      assertEquals(FailureKind.INVALID_INPUT, problem.kind());
      assertEquals("parser_output_invalid", problem.code());
      assertEquals(
          "processing", authority.ingestion().ingestionStatus(editor, task.taskId()).state());
      assertEquals(0, cancellationCount(authority));
      assertFalse(authority.ingestion().completeIngestion(claim, parsed()));
      assertCancelled(authority, task, 1);
    }
  }

  @Test
  void editorCanCancelButRetryAndBothCapabilityViewsRequireTheOriginalCreatorToRecover()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var old = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      role(task.documentId(), owner.principalId(), "reader");
      assertTrue(authority.ingestion().ingestionStatus(editor, task.taskId()).canCancel());
      assertTrue(documentTask(authority, editor, task.documentId()).canCancel());
      var cancelled = authority.ingestion().cancelIngestion(editor, task.taskId());
      assertEquals("cancelled", cancelled.state());
      assertFalse(cancelled.canRetry());
      assertFalse(documentTask(authority, editor, task.documentId()).canRetry());
      assertFalse(authority.ingestion().ingestionStatus(owner, task.taskId()).canRetry());

      var denied =
          assertThrows(
              ApplicationException.class,
              () -> authority.ingestion().retryIngestion(editor, task.taskId()));
      assertEquals(FailureKind.CONFLICT, denied.kind());
      assertEquals("authorization_changed", denied.code());
      assertEquals(1, authority.ingestion().ingestionStatus(editor, task.taskId()).attempt());

      role(task.documentId(), owner.principalId(), "editor");
      assertTrue(authority.ingestion().ingestionStatus(editor, task.taskId()).canRetry());
      assertTrue(documentTask(authority, editor, task.documentId()).canRetry());
      var retried = authority.ingestion().retryIngestion(editor, task.taskId());
      assertEquals(2, retried.attempt());
      assertEquals(task.revisionId(), retried.revisionId());
      var current = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertArrayEquals(old.content(), current.content());
      assertNotEquals(old.token(), current.token());
      assertFalse(authority.ingestion().isIngestionClaimCurrent(old));
      assertFalse(authority.ingestion().completeIngestion(old, parsed()));
      assertFalse(authority.ingestion().failIngestion(old, "parser_failed"));
      assertEquals(
          "processing", authority.ingestion().ingestionStatus(editor, task.taskId()).state());
      assertTrue(authority.ingestion().failIngestion(current, "parser_timeout"));
      assertTrue(authority.ingestion().ingestionStatus(editor, task.taskId()).canRetry());

      assertEquals(3, authority.ingestion().retryIngestion(editor, task.taskId()).attempt());
      var last = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(authority.ingestion().failIngestion(last, "parser_failed"));
      assertFalse(authority.ingestion().ingestionStatus(editor, task.taskId()).canRetry());
      assertFalse(documentTask(authority, editor, task.documentId()).canRetry());
      assertEquals(
          "ingestion_retry_limit",
          assertThrows(
                  ApplicationException.class,
                  () -> authority.ingestion().retryIngestion(editor, task.taskId()))
              .code());
      assertEquals(0, cancellationCount(authority));
    }
  }

  @Test
  void cancellationAndItsAuditRollBackTogetherWhenTheAuditInsertFails() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority, owner);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      revoke(task.documentId(), owner.principalId());
      sql(
          "CREATE TRIGGER test_block_authorization_audit BEFORE INSERT ON management_audit WHEN NEW.action='ingestion_authorization_cancelled' BEGIN SELECT RAISE(ABORT,'test audit failure'); END");

      var problem =
          assertThrows(
              ApplicationException.class,
              () -> authority.ingestion().isIngestionClaimCurrent(claim));

      assertEquals(FailureKind.UNAVAILABLE, problem.kind());
      var stillProcessing = authority.ingestion().ingestionStatus(editor, task.taskId());
      assertEquals("processing", stillProcessing.state());
      assertEquals(1, stillProcessing.attempt());
      assertEquals(
          1L,
          scalar(
              "SELECT COUNT(*) FROM ingestion_jobs WHERE id=? AND claim_token_sha256 IS NOT NULL",
              task.taskId()));
      assertEquals(
          0L, scalar("SELECT COUNT(*) FROM corpus_pages WHERE revision_id=?", task.revisionId()));
      assertEquals(
          0L,
          scalar("SELECT COUNT(*) FROM corpus_segments WHERE revision_id=?", task.revisionId()));
      assertEquals(0, cancellationCount(authority));
      sql("DROP TRIGGER test_block_authorization_audit");
      assertFalse(authority.ingestion().isIngestionClaimCurrent(claim));
      assertCancelled(authority, task, 1);
      assertEquals(1, cancellationCount(authority));
    }
  }

  @Test
  void restartRecoveryCancelsRevokedProcessingButPreservesAuthorizedFailureSemantics()
      throws Exception {
    TaskResult revoked;
    TaskResult allowed;
    IngestionClaim old;
    try (var authority = new AuthorityTestContext(directory)) {
      revoked = upload(authority, owner);
      old = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      revoke(revoked.documentId(), owner.principalId());
      allowed = upload(authority, owner);
      assertEquals(
          allowed.taskId(),
          authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow().jobId());
    }
    try (var authority = new AuthorityTestContext(directory)) {
      assertCancelled(authority, revoked, 1);
      var interrupted = authority.ingestion().ingestionStatus(owner, allowed.taskId());
      assertEquals("failed", interrupted.state());
      assertEquals("worker_interrupted", interrupted.errorCode());
      assertTrue(interrupted.canRetry());
      assertFalse(authority.ingestion().completeIngestion(old, parsed()));
      authority.ingestion().recoverIngestions();
      assertEquals(1, cancellationCount(authority));
      role(revoked.documentId(), owner.principalId(), "owner");
      assertEquals(2, authority.ingestion().retryIngestion(owner, revoked.taskId()).attempt());
      var fresh = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertEquals(old.revisionId(), fresh.revisionId());
      assertTrue(authority.ingestion().completeIngestion(fresh, parsed()));
      assertEquals(parsed(), authority.ingestion().parsedEvidence(owner, revoked.documentId()));
    }
  }

  private TaskResult upload(AuthorityTestContext authority, Actor creator) throws SQLException {
    var task =
        authority
            .ingestion()
            .uploadDocument(
                creator,
                "private-source.txt",
                "text/plain",
                "policy".getBytes(StandardCharsets.UTF_8));
    sql(
        "INSERT INTO document_acl VALUES(?,?,?)",
        task.documentId(),
        editor.principalId(),
        "editor");
    return task;
  }

  private static ParsedText parsed() {
    return new ParsedText(
        List.of(new TextPage(1, "policy")), List.of(new TextSegment(0, 1, 0, 6, "policy")));
  }

  private TaskResult documentTask(AuthorityTestContext authority, Actor actor, String documentId) {
    return authority
        .management()
        .listDocuments(actor, new DocumentQuery("", null, null, null, null, "updated_desc", 1, 100))
        .items()
        .stream()
        .filter(document -> document.documentId().equals(documentId))
        .findFirst()
        .orElseThrow()
        .latestJob();
  }

  private void assertCancelled(AuthorityTestContext authority, TaskResult original, int attempt)
      throws SQLException {
    var task = authority.ingestion().ingestionStatus(editor, original.taskId());
    assertEquals("cancelled", task.state());
    assertNull(task.errorCode());
    assertEquals(attempt, task.attempt());
    assertEquals(original.revisionId(), task.revisionId());
    assertFalse(task.canRetry());
    assertFalse(task.canCancel());
    assertEquals(
        0L,
        scalar(
            "SELECT COUNT(*) FROM ingestion_jobs WHERE id=? AND claim_token_sha256 IS NOT NULL",
            original.taskId()));
    assertEquals(
        0L, scalar("SELECT COUNT(*) FROM corpus_pages WHERE revision_id=?", original.revisionId()));
    assertEquals(
        0L,
        scalar("SELECT COUNT(*) FROM corpus_segments WHERE revision_id=?", original.revisionId()));
    assertEquals(
        0L,
        scalar(
            "SELECT COUNT(*) FROM corpus_documents WHERE document_id=? AND parsed_revision_id IS NOT NULL",
            original.documentId()));
    assertEquals(
        0L,
        scalar(
            "SELECT COUNT(*) FROM active_corpus_publications WHERE document_id=?",
            original.documentId()));
  }

  private long cancellationCount(AuthorityTestContext authority) {
    return authority.management().auditEvents(system).stream()
        .filter(event -> event.action().equals("ingestion_authorization_cancelled"))
        .count();
  }

  private void assertSensitiveValuesAbsentFromAudit(
      AuthorityTestContext authority, IngestionClaim claim) {
    var events = authority.management().auditEvents(system);
    var cancellation =
        events.stream()
            .filter(event -> event.action().equals("ingestion_authorization_cancelled"))
            .findFirst()
            .orElseThrow();
    assertEquals(system.principalId(), cancellation.actorId());
    assertEquals(owner.workspaceId(), cancellation.workspaceId());
    assertFalse(cancellation.fieldsJson().contains("role"));
    assertFalse(events.toString().contains(claim.token()));
    assertFalse(events.toString().contains("policy"));
    assertFalse(events.toString().contains(claim.filename()));
    assertTrue(cancellation.beforeSha256().matches("[a-f0-9]{64}"));
    assertTrue(cancellation.afterSha256().matches("[a-f0-9]{64}"));
  }

  private void revoke(String documentId, String principalId) throws SQLException {
    sql("DELETE FROM document_acl WHERE document_id=? AND principal_id=?", documentId, principalId);
  }

  private void role(String documentId, String principalId, String role) throws SQLException {
    sql(
        "INSERT INTO document_acl(document_id,principal_id,role) VALUES(?,?,?) ON CONFLICT(document_id,principal_id) DO UPDATE SET role=excluded.role",
        documentId,
        principalId,
        role);
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

  private long scalar(String statement, String value) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var query = connection.prepareStatement(statement)) {
      query.setString(1, value);
      try (var result = query.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }
}
