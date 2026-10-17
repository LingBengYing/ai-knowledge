package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.domain.*;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A halt and the embedding budget apply only to the batch's own import tasks. */
class DocumentBatchHaltScopeTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");
  final byte[] a = "Synthetic batch source A".getBytes(StandardCharsets.UTF_8);
  final byte[] b = "Synthetic batch source B".getBytes(StandardCharsets.UTF_8);
  final IndexTarget target = new IndexTarget("fixture", "b".repeat(64), "fixture", 2);

  private String batch(DocumentBatchService batches, int budget, byte[]... sources) {
    var files = new java.util.ArrayList<DocumentBatchCommand.File>();
    for (int i = 0; i < sources.length; i++)
      files.add(
          new DocumentBatchCommand.File(
              "source-" + i + ".txt", sources[i].length, ModelValues.sha256(sources[i])));
    return batches.create(actor, new DocumentBatchCommand("halt-scope", files, budget)).batchId();
  }

  private TaskResult parse(AuthorityTestContext authority, String name, byte[] source) {
    var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(claim, new TextParser().parse(name, "text/plain", source)));
    return authority.ingestion().ingestionStatus(actor, claim.jobId());
  }

  /** The real auto-index dispatcher adopts an existing job for the parsed revision. */
  private static void dispatchImports(AuthorityTestContext authority) {
    var dispatcher = new ImportAutoIndexService(authority.store(), () -> null, null, null);
    while (dispatcher.processNext()) {
      // Drain every pending import request.
    }
  }

  @Test
  void haltedBatchCancelsItsFirstAttemptImportIndexingAndRunsAnExplicitRetry() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String id = batch(batches, 100, a, b);
      var first = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      batches.upload(actor, id, 1, b);
      var claimB = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertTrue(authority.ingestion().failIngestion(claimB, "parser_failed"));
      var importTask = authority.indexing().createIndexing(actor, first.documentId(), target);
      dispatchImports(authority);
      assertEquals("halted", batches.status(actor, id, 0, 100).state());

      assertTrue(authority.indexing().claimIndexing(actor.workspaceId()).isEmpty());
      assertEquals(
          "cancelled", authority.indexing().indexingStatus(actor, importTask.taskId()).state());

      authority.indexing().retryIndexing(actor, importTask.taskId(), target);
      var retried = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(importTask.taskId(), retried.jobId());
      assertEquals(2, retried.attempt());
      assertTrue(authority.indexing().reserveBatchRequests(retried, 1));
      assertEquals(1, batches.status(actor, id, 0, 100).reservedRequests());
    }
  }

  @Test
  void haltedBatchCancelsQueuedImportParsingAndRunsAnExplicitRetry() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String id = batch(batches, 100, a, b);
      var failed = batches.upload(actor, id, 0, a);
      var queued = batches.upload(actor, id, 1, b);
      var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertEquals(failed.taskId(), claim.jobId());
      assertTrue(authority.ingestion().failIngestion(claim, "parser_failed"));

      assertTrue(authority.ingestion().claimIngestion(actor.workspaceId()).isEmpty());
      assertEquals(
          "cancelled", authority.ingestion().ingestionStatus(actor, queued.taskId()).state());

      assertEquals("queued", authority.ingestion().retryIngestion(actor, failed.taskId()).state());
      var retried = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertEquals(failed.taskId(), retried.jobId());
    }
  }

  @Test
  void indexingOutsideTheImportRequestIgnoresTheHaltAndSpendsNoBudget() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String id = batch(batches, 1, a);
      var file = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      // No indexing processor: the import request fails and halts the batch.
      dispatchImports(authority);
      var halted = batches.status(actor, id, 0, 100);
      assertEquals("halted", halted.state());

      var manual = authority.indexing().createIndexing(actor, file.documentId(), target);
      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(manual.taskId(), claim.jobId());
      assertTrue(authority.indexing().reserveBatchRequests(claim, 50));
      authority.indexing().recordBatchUsage(claim, 50, 50, true);
      var status = batches.status(actor, id, 0, 100);
      assertEquals(0, status.reservedRequests());
      assertEquals(0, status.observedRequests());
      assertEquals(halted.haltedReason(), status.haltedReason());
    }
  }
}
