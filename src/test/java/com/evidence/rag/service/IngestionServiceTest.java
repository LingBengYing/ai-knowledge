package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.web.HttpProblemMapper;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestionServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void uploadMetadataAdmissionNeedsNoFakeContentAndDoesNotAdmitInvalidFiles() {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = authority.ingestion();
      assertEquals("application/pdf", service.prepareUpload("合成资料.PDF"));
      assertEquals("text/markdown", service.prepareUpload("NOTES.MD"));
      assertEquals("text/plain", service.prepareUpload("笔记.txt"));
      for (String invalid :
          List.of(
              "../notes.txt", "a\\b.txt", ".txt", "a.png", "a\n.txt", "a".repeat(256) + ".pdf")) {
        assertEquals(
            "unsupported_document",
            assertThrows(ApplicationException.class, () -> service.prepareUpload(invalid)).code());
      }
      assertThrows(ApplicationException.class, () -> service.prepareUpload(null));
      assertEquals(
          "unsupported_document",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.uploadDocument(
                          owner, "valid.pdf", "application/pdf", new byte[] {'x'}))
              .code());
      assertEquals(0L, authority.listDocuments(owner, Map.of()).get("total"));
    }
  }

  @Test
  void whitespaceRichEvidenceFromRealChildCommitsWithoutWeakeningLocatorValidation() {
    try (var authority = new AuthorityTestContext(directory);
        var parser = new ProcessTextParser(Duration.ofSeconds(10))) {
      byte[] content =
          (" ".repeat(1100) + "x批准😀" + " ".repeat(200) + "y禁止🚫").getBytes(StandardCharsets.UTF_8);
      var task = authority.uploadDocument(owner, "spacing.txt", "text/plain", content);
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
      assertTrue(authority.completeIngestion(claim, parsed));
      assertEquals("parsed", authority.ingestionStatus(owner, claim.jobId()).get("state"));
      assertEquals(parsed, authority.parsedEvidence(owner, (String) task.get("document_id")));
    }
  }

  @Test
  void realUploadQueuesImmutableBytesAndParsingPublishesNoActiveIndex() {
    try (var authority = new AuthorityTestContext(directory)) {
      byte[] bytes = "政策：仅依据文档。".getBytes(StandardCharsets.UTF_8);
      var task = authority.uploadDocument(owner, "policy.txt", "text/plain", bytes);
      String document = (String) task.get("document_id");
      assertEquals("queued", task.get("state"));
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      assertEquals(document, claim.documentId());
      bytes[0] = 0;
      assertEquals("政策：仅依据文档。", new String(claim.content(), StandardCharsets.UTF_8));
      assertTrue(authority.isIngestionClaimCurrent(claim));
      var parsed = new TextParser().parse(claim.filename(), claim.mimeType(), claim.content());
      assertTrue(authority.completeIngestion(claim, parsed));
      assertEquals("parsed", authority.ingestionStatus(owner, claim.jobId()).get("state"));
      assertEquals(parsed, authority.parsedEvidence(owner, document));
      @SuppressWarnings("unchecked")
      var item =
          ((List<Map<String, Object>>) authority.listDocuments(owner, Map.of()).get("items"))
              .getFirst();
      assertEquals(false, item.get("synthetic_fixture"));
      assertEquals("parsed", item.get("status"));
      assertEquals(false, item.get("can_answer"));
      assertNull(item.get("active_revision_id"));
      assertTrue(((Number) item.get("segment_count")).intValue() > 0);
      assertFalse(authority.completeIngestion(claim, parsed));
      assertFalse(authority.failIngestion(claim, "parser_failed"));
      fails(409, () -> authority.cancelIngestion(owner, claim.jobId()));
      fails(409, () -> authority.retryIngestion(owner, claim.jobId()));
      assertEquals(0L, authority.listDocuments(owner, Map.of("status", "ready")).get("total"));
      assertEquals(1L, authority.listDocuments(owner, Map.of("status", "parsed")).get("total"));
    }
  }

  @Test
  void cancelledAndFailedAttemptsFenceOldWorkersAndNeverReplaceSource() {
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority);
      String id = (String) task.get("task_id");
      assertEquals(true, task.get("can_cancel"));
      assertEquals(false, task.get("can_retry"));
      assertEquals("cancelled", authority.cancelIngestion(owner, id).get("state"));
      assertTrue(authority.claimIngestion(owner.workspaceId()).isEmpty());
      assertEquals(2, authority.retryIngestion(owner, id).get("attempt"));
      var old = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      byte[] returned = old.content();
      returned[0] = 0;
      assertNotEquals(0, old.content()[0]);
      assertFalse(old.toString().contains(old.token()));
      assertFalse(old.toString().contains("secret-policy"));
      assertEquals("cancelled", authority.cancelIngestion(owner, id).get("state"));
      assertEquals(3, authority.retryIngestion(owner, id).get("attempt"));
      var current = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      assertEquals(old.documentId(), current.documentId());
      assertEquals(old.revisionId(), current.revisionId());
      assertArrayEquals(old.content(), current.content());
      assertNotEquals(old.token(), current.token());
      assertFalse(authority.isIngestionClaimCurrent(old));
      assertFalse(authority.completeIngestion(old, parsed()));
      assertFalse(authority.failIngestion(old, "parser_timeout"));
      assertTrue(authority.failIngestion(current, "parser_timeout"));
      var failed = authority.ingestionStatus(owner, id);
      assertEquals("failed", failed.get("state"));
      assertEquals("parser_timeout", failed.get("error_code"));
      assertEquals(false, failed.get("can_retry"));
      assertEquals(
          "ingestion_retry_limit", fails(409, () -> authority.retryIngestion(owner, id)).code());
      assertFalse(failed.toString().contains(current.token()));
      assertFalse(failed.toString().contains("secret-policy"));
      fails(404, () -> authority.parsedEvidence(owner, current.documentId()));
    }
  }

  @Test
  void readsAndActionsRequireCurrentAclAndWorkspaceInTheTransaction() throws Exception {
    var reader = new Actor(owner.workspaceId(), "reader");
    var editor = new Actor(owner.workspaceId(), "editor");
    var other = new Actor("other", owner.principalId());
    try (var authority = new AuthorityTestContext(directory)) {
      var task = upload(authority);
      String id = (String) task.get("task_id"), document = (String) task.get("document_id");
      sql("INSERT INTO document_acl VALUES('" + document + "','reader','reader')");
      sql("INSERT INTO document_acl VALUES('" + document + "','editor','editor')");
      assertEquals(false, authority.ingestionStatus(reader, id).get("can_cancel"));
      fails(404, () -> authority.cancelIngestion(reader, id));
      fails(404, () -> authority.retryIngestion(reader, id));
      fails(404, () -> authority.ingestionStatus(other, id));
      fails(404, () -> authority.cancelIngestion(other, id));
      assertTrue(authority.claimIngestion("other").isEmpty());
      assertEquals("cancelled", authority.cancelIngestion(editor, id).get("state"));
      assertEquals("queued", authority.retryIngestion(editor, id).get("state"));
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(authority.completeIngestion(claim, parsed()));
      assertEquals(parsed(), authority.parsedEvidence(reader, document));
      fails(404, () -> authority.parsedEvidence(other, document));
      sql(
          "DELETE FROM document_acl WHERE document_id='"
              + document
              + "' AND principal_id='reader'");
      fails(404, () -> authority.ingestionStatus(reader, id));
      fails(404, () -> authority.parsedEvidence(reader, document));
      fails(422, () -> authority.ingestionStatus(null, id));
      fails(422, () -> authority.ingestionStatus(owner, ""));
    }
  }

  @Test
  void everyClaimIdentityAndTokenAreValidatedBeforeMutation() {
    try (var authority = new AuthorityTestContext(directory)) {
      upload(authority);
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
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
        assertFalse(authority.isIngestionClaimCurrent(forged), field);
        assertFalse(authority.failIngestion(forged, "parser_failed"), field);
        assertFalse(authority.completeIngestion(forged, parsed()), field);
      }
      assertFalse(authority.isIngestionClaimCurrent(null));
      var noToken =
          new IngestionClaim(
              claim.jobId(),
              claim.documentId(),
              claim.revisionId(),
              claim.workspaceId(),
              claim.attempt(),
              null,
              claim.filename(),
              claim.mimeType(),
              claim.parserRevision(),
              claim.content());
      assertFalse(authority.isIngestionClaimCurrent(noToken));
      var shortToken =
          new IngestionClaim(
              claim.jobId(),
              claim.documentId(),
              claim.revisionId(),
              claim.workspaceId(),
              claim.attempt(),
              "short",
              claim.filename(),
              claim.mimeType(),
              claim.parserRevision(),
              claim.content());
      assertFalse(authority.isIngestionClaimCurrent(shortToken));
      var changedBytes =
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
      fails(422, () -> authority.completeIngestion(changedBytes, parsed()));
      fails(422, () -> authority.failIngestion(claim, null));
      fails(422, () -> authority.failIngestion(claim, "unsafe secret detail"));
      assertTrue(authority.isIngestionClaimCurrent(claim));
      assertTrue(authority.completeIngestion(claim, parsed()));
    }
  }

  @Test
  void recoveryFailsAbandonedProcessingButPreservesQueuedAndEvidence() {
    IngestionClaim abandoned;
    String queued;
    try (var authority = new AuthorityTestContext(directory)) {
      upload(authority);
      abandoned = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      queued = (String) upload(authority).get("task_id");
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var failed = authority.ingestionStatus(owner, abandoned.jobId());
      assertEquals("failed", failed.get("state"));
      assertEquals("worker_interrupted", failed.get("error_code"));
      assertEquals(true, failed.get("can_retry"));
      assertFalse(authority.completeIngestion(abandoned, parsed()));
      assertEquals("queued", authority.ingestionStatus(owner, queued).get("state"));
      var queuedClaim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      assertEquals(queued, queuedClaim.jobId());
      assertTrue(authority.completeIngestion(queuedClaim, parsed()));
      authority.retryIngestion(owner, abandoned.jobId());
      var retry = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      assertArrayEquals(abandoned.content(), retry.content());
      assertTrue(authority.completeIngestion(retry, parsed()));
    }
    try (var authority = new AuthorityTestContext(directory)) {
      assertEquals("parsed", authority.ingestionStatus(owner, abandoned.jobId()).get("state"));
      assertEquals(parsed(), authority.parsedEvidence(owner, abandoned.documentId()));
    }
  }

  @Test
  void invalidLocatorsNeverPartiallyPersistAndUnicodeOffsetsAreCodepoints() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      upload(authority);
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      var invalid = new ArrayList<ParsedText>();
      invalid.add(null);
      invalid.add(new ParsedText(List.of(), List.of()));
      invalid.add(new ParsedText(List.of(new TextPage(1, "text")), List.of()));
      invalid.add(single(2, "text", 0, 1, 0, 4, "text"));
      invalid.add(single(1, null, 0, 1, 0, 4, "text"));
      invalid.add(single(1, "text\u0000", 0, 1, 0, 4, "text"));
      invalid.add(single(1, "text\uD800", 0, 1, 0, 4, "text"));
      invalid.add(single(1, "text", 1, 1, 0, 4, "text"));
      invalid.add(single(1, "text", 0, 0, 0, 4, "text"));
      invalid.add(single(1, "text", 0, 2, 0, 4, "text"));
      invalid.add(single(1, "text", 0, 1, -1, 4, "text"));
      invalid.add(single(1, "text", 0, 1, 2, 2, "text"));
      invalid.add(single(1, "text", 0, 1, 0, 5, "text"));
      invalid.add(single(1, "text", 0, 1, 0, 4, null));
      invalid.add(single(1, "text", 0, 1, 0, 4, "    "));
      invalid.add(single(1, "text", 0, 1, 0, 4, "fake"));
      invalid.add(single(1, "x".repeat(1201), 0, 1, 0, 1201, "x".repeat(1201)));
      invalid.add(single(1, "x".repeat(1_000_001), 0, 1, 0, 1, "x"));
      invalid.add(
          new ParsedText(
              List.of(new TextPage(1, "text")),
              List.of(new TextSegment(0, 1, 1, 4, "ext"), new TextSegment(1, 1, 0, 4, "text"))));
      invalid.add(
          new ParsedText(
              List.of(new TextPage(1, "x"), new TextPage(2, "x")),
              List.of(new TextSegment(0, 2, 0, 1, "x"), new TextSegment(1, 1, 0, 1, "x"))));
      for (var output : invalid) {
        assertEquals(
            "parser_output_invalid",
            fails(422, () -> authority.completeIngestion(claim, output)).code());
        assertTrue(authority.isIngestionClaimCurrent(claim));
        assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_segments"));
      }
      var valid = single(1, "😀中文\n\t\f", 0, 1, 0, 3, "😀中文");
      assertTrue(authority.completeIngestion(claim, valid));
      assertEquals(valid, authority.parsedEvidence(owner, claim.documentId()));
    }
  }

  @Test
  void unicodeSpaceOnlySegmentsAreRejectedAtTheAuthorityBoundary() {
    try (var authority = new AuthorityTestContext(directory)) {
      upload(authority);
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      fails(
          422, () -> authority.completeIngestion(claim, single(1, "\u00a0", 0, 1, 0, 1, "\u00a0")));
      assertTrue(authority.isIngestionClaimCurrent(claim));
    }
  }

  @Test
  void transactionalAuditFailureRollsBackUploadAndAllParsedEvidence() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      sql(
          "CREATE TRIGGER injected_failure BEFORE INSERT ON management_audit BEGIN SELECT RAISE(ABORT,'secret_internal_failure'); END");
      assertFalse(
          fails(503, () -> upload(authority)).getMessage().contains("secret_internal_failure"));
      assertEquals(0L, authority.listDocuments(owner, Map.of()).get("total"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_documents"));
      sql("DROP TRIGGER injected_failure");
      upload(authority);
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      sql(
          "CREATE TRIGGER injected_failure BEFORE INSERT ON management_audit BEGIN SELECT RAISE(ABORT,'secret_internal_failure'); END");
      fails(503, () -> authority.completeIngestion(claim, parsed()));
      assertTrue(authority.isIngestionClaimCurrent(claim));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_pages"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM corpus_segments"));
      assertEquals(
          0, scalar("SELECT COUNT(*) FROM corpus_documents WHERE parsed_revision_id IS NOT NULL"));
      sql("DROP TRIGGER injected_failure");
      assertTrue(authority.completeIngestion(claim, parsed()));
      String audit =
          authority.auditEvents(new Actor(owner.workspaceId(), "system:ingestion")).toString();
      assertFalse(audit.contains("secret-policy"));
      assertFalse(audit.contains(claim.token()));
      assertFalse(audit.contains("😀中文"));
    }
  }

  @Test
  void pendingQuotaIsAtomicPerWorkspaceAndRetryCannotOverfillIt() {
    try (var authority = new AuthorityTestContext(directory)) {
      String cancelled = (String) upload(authority).get("task_id");
      authority.cancelIngestion(owner, cancelled);
      String first = null;
      for (int i = 0; i < 32; i++) {
        String id = (String) upload(authority).get("task_id");
        if (first == null) {
          first = id;
        }
      }
      assertEquals("ingestion_quota_exceeded", fails(409, () -> upload(authority)).code());
      fails(409, () -> authority.retryIngestion(owner, cancelled));
      assertEquals(33L, authority.listDocuments(owner, Map.of()).get("total"));
      assertEquals(
          "queued",
          authority
              .uploadDocument(
                  new Actor("other", "owner"), "other.txt", "text/plain", new byte[] {65})
              .get("state"));
      authority.cancelIngestion(owner, first);
      assertEquals("queued", authority.retryIngestion(owner, cancelled).get("state"));
      assertEquals(32L, authority.listDocuments(owner, Map.of("status", "queued")).get("total"));
    }
  }

  @Test
  void storedOriginalQuotaCountsCancelledFilesAndDoesNotAcceptPartialUpload() {
    byte[] bytes = new byte[20 * 1024 * 1024];
    Arrays.fill(bytes, (byte) 'a');
    try (var authority = new AuthorityTestContext(directory)) {
      for (int i = 0; i < 12; i++) {
        var task = authority.uploadDocument(owner, "large.txt", "application/octet-stream", bytes);
        authority.cancelIngestion(owner, (String) task.get("task_id"));
      }
      var last =
          authority.uploadDocument(
              owner, "last.txt", "text/plain", Arrays.copyOf(bytes, 16 * 1024 * 1024));
      authority.cancelIngestion(owner, (String) last.get("task_id"));
      assertEquals("ingestion_quota_exceeded", fails(409, () -> upload(authority)).code());
      assertEquals(13L, authority.listDocuments(owner, Map.of()).get("total"));
    }
  }

  @Test
  void invalidUploadsDoNotCreateRowsAndSafeMimeIsPinned() {
    try (var authority = new AuthorityTestContext(directory)) {
      fails(422, () -> authority.uploadDocument(null, "x.txt", "text/plain", new byte[] {1}));
      fails(422, () -> authority.uploadDocument(owner, "x.txt", "text/plain", null));
      fails(422, () -> authority.uploadDocument(owner, "x.txt", "text/plain", new byte[0]));
      fails(
          422,
          () ->
              authority.uploadDocument(
                  owner, "x.txt", "text/plain", new byte[TextParser.MAX_BYTES + 1]));
      fails(422, () -> authority.uploadDocument(owner, "../x.txt", "text/plain", new byte[] {1}));
      assertEquals(0L, authority.listDocuments(owner, Map.of()).get("total"));
      authority.uploadDocument(owner, "readme.MD", "application/octet-stream", new byte[] {65});
      assertEquals(
          "text/markdown", authority.claimIngestion(owner.workspaceId()).orElseThrow().mimeType());
      authority.uploadDocument(
          owner,
          "text.PDF",
          "application/octet-stream",
          "%PDF invalid body".getBytes(StandardCharsets.UTF_8));
      assertEquals(
          "application/pdf",
          authority.claimIngestion(owner.workspaceId()).orElseThrow().mimeType());
    }
  }

  @Test
  void managementEditsAndRawSqlCannotReplaceRegisteredOrParsedEvidence() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      upload(authority);
      var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
      var parsed = parsed();
      authority.completeIngestion(claim, parsed);
      var folder = authority.createFolder(owner, Map.of("name", "整理"));
      var view =
          authority.updateDocument(
              owner,
              claim.documentId(),
              Map.of(
                  "display_name",
                  "标题",
                  "folder_id",
                  folder.get("folder_id"),
                  "tags",
                  List.of("tag")));
      assertEquals("original.txt", view.get("filename"));
      assertNull(view.get("active_revision_id"));
      assertEquals(parsed, authority.parsedEvidence(owner, claim.documentId()));
      assertTrue(authority.claimIngestion(owner.workspaceId()).isEmpty());
      for (String statement :
          List.of(
              "UPDATE documents SET active_revision_id='changed'",
              "UPDATE documents SET filename='changed.txt'",
              "UPDATE corpus_documents SET original_blob=X'01'",
              "UPDATE corpus_documents SET initial_revision_id='changed'",
              "UPDATE corpus_documents SET parsed_revision_id=NULL",
              "UPDATE corpus_documents SET active_revision_id='published-without-index'",
              "UPDATE corpus_revisions SET parser_revision='changed'",
              "UPDATE corpus_revisions SET segment_count=0",
              "UPDATE corpus_pages SET text='changed'",
              "UPDATE corpus_segments SET start_offset=1",
              "UPDATE ingestion_jobs SET revision_id='changed'",
              "UPDATE ingestion_jobs SET state='queued'",
              "DELETE FROM corpus_documents",
              "DELETE FROM corpus_revisions",
              "DELETE FROM corpus_pages",
              "DELETE FROM corpus_segments",
              "DELETE FROM ingestion_jobs",
              "INSERT INTO corpus_pages VALUES('" + claim.revisionId() + "',2,'later','hash')",
              "INSERT INTO corpus_segments VALUES('new','"
                  + claim.revisionId()
                  + "',1,1,0,1,'s','hash')")) {
        assertThrows(SQLException.class, () -> sql(statement), statement);
      }
      assertEquals(parsed, authority.parsedEvidence(owner, claim.documentId()));
    }
  }

  private Map<String, Object> upload(AuthorityTestContext authority) {
    return authority.uploadDocument(
        owner, "original.txt", "text/plain", "secret-policy".getBytes(StandardCharsets.UTF_8));
  }

  private static ParsedText parsed() {
    return single(1, "secret-policy", 0, 1, 0, 13, "secret-policy");
  }

  private static ParsedText single(
      int number, String page, int ordinal, int sourcePage, int start, int end, String segment) {
    return new ParsedText(
        List.of(new TextPage(number, page)),
        List.of(new TextSegment(ordinal, sourcePage, start, end, segment)));
  }

  private static ApplicationException fails(int status, Runnable action) {
    var failure = assertThrows(ApplicationException.class, action::run);
    assertEquals(status, HttpProblemMapper.status(failure));
    return failure;
  }

  private void sql(String statement) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var command = connection.createStatement()) {
      command.execute(statement);
    }
  }

  private int scalar(String statement) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var command = connection.createStatement();
        var rows = command.executeQuery(statement)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }
}
