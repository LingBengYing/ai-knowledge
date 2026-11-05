package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

/** Current creator authorization is observed through the real authority interfaces. */
class IngestionAuthorizationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");
  private final Actor editor = new Actor("org-main", "editor");
  private final Actor system = new Actor("org-main", "system:ingestion");

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
