package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportAutoIndexServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void newlyUploadedOriginalKeepsAutomaticIndexIntentAcrossRestart() {
    try (var authority = new AuthorityTestContext(directory)) {
      authority
          .ingestion()
          .uploadDocument(
              owner, "auto.txt", "text/plain", "Synthetic import".getBytes(StandardCharsets.UTF_8));
      assertPending(authority);
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      assertPending(reopened);
    }
  }

  @Test
  void parsedImportQueuesExactlyOneExistingIndexTaskAfterRestart() {
    String documentId;
    try (var authority = new AuthorityTestContext(directory)) {
      var upload =
          authority
              .ingestion()
              .uploadDocument(
                  owner,
                  "auto.txt",
                  "text/plain",
                  "Synthetic import".getBytes(StandardCharsets.UTF_8));
      documentId = upload.documentId();
      var claim = authority.ingestion().claimIngestion("org").orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  claim,
                  new ParsedText(
                      List.of(new TextPage(1, "Synthetic import")),
                      List.of(new TextSegment(0, 1, 0, 16, "Synthetic import")))));
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var processor =
          new IndexingTaskProcessor(
              authority.indexing(),
              "org",
              new IndexTarget("embedding", "b".repeat(64), "model", 2),
              Duration.ofSeconds(1),
              deadline -> {
                throw new AssertionError("queueing does not call models");
              });
      var automatic = new ImportAutoIndexService(authority.store(), () -> processor, null, null);
      assertTrue(automatic.processNext());
      var claim = authority.indexing().claimIndexing("org").orElseThrow();
      assertEquals(documentId, claim.documentId());
      assertFalse(automatic.processNext());
      assertTrue(authority.indexing().claimIndexing("org").isEmpty());
    }
  }

  @Test
  void missingRuntimeFailsVisiblyAndIsNotRetriedAfterConfigurationOrRestart() {
    try (var authority = new AuthorityTestContext(directory)) {
      parsed(authority);
      var automatic = new ImportAutoIndexService(authority.store(), () -> null, null, null);
      assertTrue(automatic.processNext());
      assertEquals("failed", progress(authority).get("state"));
      assertEquals("indexing_unavailable", progress(authority).get("error_code"));
      assertFalse(automatic.processNext());
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var automatic =
          new ImportAutoIndexService(reopened.store(), () -> processor(reopened), null, null);
      assertFalse(automatic.processNext());
      assertTrue(reopened.indexing().claimIndexing("org").isEmpty());
      assertEquals("failed", progress(reopened).get("state"));
    }
  }

  @Test
  void disabledAdmissionDoesNotBackfillHistoricalParsedOriginals() {
    try (var authority = new AuthorityTestContext(directory)) {
      authority.ingestion().setAutomaticIndexingEnabled(false);
      parsed(authority);
      assertNull(document(authority).get("auto_index"));
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var automatic =
          new ImportAutoIndexService(reopened.store(), () -> processor(reopened), null, null);
      assertFalse(automatic.processNext());
      assertTrue(reopened.indexing().claimIndexing("org").isEmpty());
    }
  }

  @Test
  void committedIndexTaskClosesRestartReceiptGapWithoutDuplicate() {
    String taskId;
    try (var authority = new AuthorityTestContext(directory)) {
      String documentId = parsed(authority);
      authority
          .store()
          .transaction(() -> new ImportIndexRepository(authority.store()).claim().orElseThrow());
      taskId = processor(authority).create(owner, documentId).taskId();
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var automatic =
          new ImportAutoIndexService(reopened.store(), () -> processor(reopened), null, null);
      assertFalse(automatic.processNext());
      assertEquals(taskId, progress(reopened).get("task_id"));
      assertEquals("submitted", progress(reopened).get("state"));
      assertEquals(taskId, reopened.indexing().claimIndexing("org").orElseThrow().jobId());
      assertTrue(reopened.indexing().claimIndexing("org").isEmpty());
    }
  }

  @Test
  void replacementQueuesItsCandidateWithoutChangingTheCurrentOriginal() {
    try (var authority = new AuthorityTestContext(directory)) {
      authority.ingestion().setAutomaticIndexingEnabled(false);
      String id = parsed(authority);
      String base = (String) ((Map<?, ?>) document(authority).get("latest_job")).get("revision_id");
      var replacement =
          new DocumentReplacementService(
              authority.store(),
              new DocumentUpdateRepository(authority.store()),
              new ManagementRepository(authority.store()),
              new DocumentPermissionPolicy(),
              authority.ingestion(),
              () -> processor(authority),
              null,
              null,
              true);
      var uploaded =
          replacement.upload(
              owner,
              id,
              base,
              "new.txt",
              "text/plain",
              "Replacement text".getBytes(StandardCharsets.UTF_8));
      var parse = authority.ingestion().claimIngestion("org").orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  parse,
                  new ParsedText(
                      List.of(new TextPage(1, "Replacement text")),
                      List.of(new TextSegment(0, 1, 0, 16, "Replacement text")))));
      var automatic =
          new ImportAutoIndexService(authority.store(), () -> processor(authority), null, null);
      assertTrue(automatic.processNext());
      var claim = authority.indexing().claimIndexing("org").orElseThrow();
      assertEquals(uploaded.candidateRevisionId(), claim.revisionId());
      assertEquals(
          "Synthetic import",
          authority.ingestion().parsedEvidence(owner, id).pages().getFirst().text());
      assertFalse(automatic.processNext());
    }
  }

  @Test
  void admissionRejectsAnotherRevisionBeforeCreatingAnyIndexTask() {
    try (var authority = new AuthorityTestContext(directory)) {
      String id = parsed(authority);
      assertThrows(
          ApplicationException.class,
          () -> processor(authority).createImported(owner, id, "different-revision"));
      assertTrue(authority.indexing().claimIndexing("org").isEmpty());
      assertTrue(
          new ImportAutoIndexService(authority.store(), () -> processor(authority), null, null)
              .processNext());
      assertEquals(id, authority.indexing().claimIndexing("org").orElseThrow().documentId());
    }
  }

  @Test
  void uncommittedCorpusDispatchRecoversButParseFailureNeverRunsIndexing() {
    try (var authority = new AuthorityTestContext(directory)) {
      parsed(authority);
      authority
          .store()
          .transaction(() -> new ImportIndexRepository(authority.store()).claim().orElseThrow());
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var automatic =
          new ImportAutoIndexService(authority.store(), () -> processor(authority), null, null);
      assertTrue(automatic.processNext());
      assertFalse(automatic.processNext());
      assertNotNull(authority.indexing().claimIndexing("org").orElseThrow());
      var failed =
          authority
              .ingestion()
              .uploadDocument(
                  owner, "failed.txt", "text/plain", "Invalid".getBytes(StandardCharsets.UTF_8));
      var claim = authority.ingestion().claimIngestion("org").orElseThrow();
      assertTrue(authority.ingestion().failIngestion(claim, "parser_output_invalid"));
      assertTrue(automatic.processNext());
      var state =
          authority
              .store()
              .transaction(
                  () -> new ImportIndexRepository(authority.store()).latest(failed.documentId()));
      assertEquals("failed", state.state());
      assertEquals("parsing_incomplete", state.errorCode());
      assertFalse(automatic.processNext());
      assertTrue(authority.indexing().claimIndexing("org").isEmpty());
    }
  }

  private String parsed(AuthorityTestContext authority) {
    var task =
        authority
            .ingestion()
            .uploadDocument(
                owner,
                "auto.txt",
                "text/plain",
                "Synthetic import".getBytes(StandardCharsets.UTF_8));
    var claim = authority.ingestion().claimIngestion("org").orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(
                claim,
                new ParsedText(
                    List.of(new TextPage(1, "Synthetic import")),
                    List.of(new TextSegment(0, 1, 0, 16, "Synthetic import")))));
    return task.documentId();
  }

  private IndexingTaskProcessor processor(AuthorityTestContext authority) {
    return new IndexingTaskProcessor(
        authority.indexing(),
        "org",
        new IndexTarget("embedding", "b".repeat(64), "model", 2),
        Duration.ofSeconds(1),
        deadline -> {
          throw new AssertionError("queueing does not call models");
        });
  }

  private Map<?, ?> document(AuthorityTestContext authority) {
    return (Map<?, ?>) ((List<?>) authority.listDocuments(owner, Map.of()).get("items")).getFirst();
  }

  private Map<?, ?> progress(AuthorityTestContext authority) {
    return (Map<?, ?>) document(authority).get("auto_index");
  }

  private void assertPending(AuthorityTestContext authority) {
    var documents = (List<?>) authority.listDocuments(owner, Map.of()).get("items");
    var document = (Map<?, ?>) documents.getFirst();
    var automatic = (Map<?, ?>) document.get("auto_index");
    assertNotNull(automatic);
    assertEquals("pending", automatic.get("state"));
    assertEquals(false, document.get("can_answer"));
  }
}
