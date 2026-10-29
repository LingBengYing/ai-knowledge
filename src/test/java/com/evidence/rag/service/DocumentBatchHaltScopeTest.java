package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.*;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Only the embedding budget and an uncertain usage audit halt a batch, and a halt applies only to
 * the batch's own automatic import tasks. Any other failure, or one cancelled task, fails one file.
 */
class DocumentBatchHaltScopeTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");
  final byte[] a = "Synthetic batch source A".getBytes(StandardCharsets.UTF_8);
  final byte[] b = "Synthetic batch source B".getBytes(StandardCharsets.UTF_8);
  final IndexTarget target = new IndexTarget("fixture", "b".repeat(64), "fixture", 2);

  private static DocumentBatchService batches(AuthorityTestContext authority) {
    return new DocumentBatchService(
        authority.store(), authority.ingestion(), authority.indexing(), 10000, 2);
  }

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

  /** The indexing processor's path when the budget refuses a claimed attempt. */
  private static void refuse(AuthorityTestContext authority, IndexClaim claim, int requests) {
    assertFalse(authority.indexing().reserveBatchRequests(claim, requests));
    assertTrue(authority.indexing().cancelUnreservedIndexing(claim));
  }

  @Test
  void aParseFailureFailsOnlyThatFileAndTheRestOfTheBatchKeepsRunning() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 100, a, b);
      var failed = batches.upload(actor, id, 0, a);
      var queued = batches.upload(actor, id, 1, b);
      var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertEquals(failed.taskId(), claim.jobId());
      assertTrue(authority.ingestion().failIngestion(claim, "parser_failed"));

      var running = batches.status(actor, id, 0, 100);
      assertEquals("processing", running.state());
      assertNull(running.haltedReason());
      assertEquals(1, running.retryableFiles());
      assertEquals(List.of("retry_failed", "cancel"), running.availableActions());
      assertEquals(queued.taskId(), parse(authority, "source-1.txt", b).taskId());
    }
  }

  @Test
  void cancellingOneTaskFailsOnlyThatFile() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 100, a, b);
      var cancelled = batches.upload(actor, id, 0, a);
      var queued = batches.upload(actor, id, 1, b);
      assertEquals(
          "cancelled", authority.ingestion().cancelIngestion(actor, cancelled.taskId()).state());

      assertNull(batches.status(actor, id, 0, 100).haltedReason());
      assertEquals(queued.taskId(), parse(authority, "source-1.txt", b).taskId());
    }
  }

  @Test
  void aFailedDispatchFailsOnlyThatFileAndTheBulkRetryDispatchesItAgain() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 100, a);
      var file = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      // No indexing processor: the import request fails, the batch does not halt.
      dispatchImports(authority);
      var settled = batches.status(actor, id, 0, 100);
      assertNull(settled.haltedReason());
      assertEquals("completed_with_failures", settled.state());
      assertEquals(1, settled.retryableFiles());

      var retried = batches.retryFailed(actor, id);
      assertEquals(1, retried.affected());
      var task = authority.indexing().createIndexing(actor, file.documentId(), target);
      dispatchImports(authority);
      assertEquals(
          task.taskId(), batches.status(actor, id, 0, 100).items().getFirst().indexingTaskId());
    }
  }

  @Test
  void aParseRetryReentersAutoIndex() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 100, a);
      var file = batches.upload(actor, id, 0, a);
      var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertTrue(authority.ingestion().failIngestion(claim, "parser_failed"));
      // The failed parse makes the automatic index request fail as parsing_incomplete.
      dispatchImports(authority);

      assertEquals(1, batches.retryFailed(actor, id).affected());
      parse(authority, "source-0.txt", a);
      var task = authority.indexing().createIndexing(actor, file.documentId(), target);
      dispatchImports(authority);
      var item = batches.status(actor, id, 0, 100).items().getFirst();
      assertEquals(task.taskId(), item.indexingTaskId());
      assertNull(item.errorCode());
    }
  }

  @Test
  void budgetHaltCancelsAutomaticImportIndexingRunsAnExplicitRetryAndResumes() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 1, a, b);
      var first = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      var second = batches.upload(actor, id, 1, b);
      parse(authority, "source-1.txt", b);
      var taskA = authority.indexing().createIndexing(actor, first.documentId(), target);
      var taskB = authority.indexing().createIndexing(actor, second.documentId(), target);
      dispatchImports(authority);

      var claimA = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(taskA.taskId(), claimA.jobId());
      refuse(authority, claimA, 50);
      var halted = batches.status(actor, id, 0, 100);
      assertEquals("halted", halted.state());
      assertEquals("embedding_budget_exhausted", halted.haltedReason());
      assertTrue(halted.availableActions().contains("resume"));

      // The halted batch's automatic work stops visibly.
      assertTrue(authority.indexing().claimIndexing(actor.workspaceId()).isEmpty());
      assertEquals("cancelled", authority.indexing().indexingStatus(actor, taskB.taskId()).state());

      // An explicit user retry still runs, inside the budget.
      authority.indexing().retryIndexing(actor, taskB.taskId(), target);
      var retried = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(taskB.taskId(), retried.jobId());
      assertEquals(2, retried.attempt());
      assertTrue(authority.indexing().reserveBatchRequests(retried, 1));
      assertEquals(1, batches.status(actor, id, 0, 100).reservedRequests());
      authority.indexing().recordBatchUsage(retried, 1, 1, true, 1);
      assertTrue(authority.indexing().failIndexing(retried, "indexing_failed"));

      // Resume with a larger budget requeues the cancelled automatic task as a new attempt.
      var resumed = batches.resume(actor, id, 100);
      assertEquals(1, resumed.affected());
      assertNull(resumed.batch().haltedReason());
      assertEquals(100, resumed.batch().requestLimit());
      var again = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(taskA.taskId(), again.jobId());
      assertEquals(2, again.attempt());
      assertTrue(authority.indexing().reserveBatchRequests(again, 50));
    }
  }

  @Test
  void aBudgetShortfallAfterACancelKeepsTheBatchCancelled() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 1, a, b);
      var first = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      var second = batches.upload(actor, id, 1, b);
      parse(authority, "source-1.txt", b);
      authority.indexing().createIndexing(actor, first.documentId(), target);
      var taskB = authority.indexing().createIndexing(actor, second.documentId(), target);
      dispatchImports(authority);

      var cancelled = batches.cancel(actor, id);
      assertEquals("cancelled", cancelled.batch().state());
      assertEquals("batch_cancelled", cancelled.batch().haltedReason());

      // An explicit retry still runs after the cancel, but the budget cannot cover it.
      authority.indexing().retryIndexing(actor, taskB.taskId(), target);
      var retried = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(taskB.taskId(), retried.jobId());
      assertFalse(authority.indexing().reserveBatchRequests(retried, 50));

      // The refusal must not turn the user's cancel into a resumable budget halt.
      var status = batches.status(actor, id, 0, 100);
      assertEquals("batch_cancelled", status.haltedReason());
      assertEquals("cancelled", status.state());
    }
  }

  @Test
  void indexingOutsideTheImportRequestIgnoresTheHaltAndSpendsNoBudget() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 1, a, b);
      var file = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      var second = batches.upload(actor, id, 1, b);
      parse(authority, "source-1.txt", b);
      var imported = authority.indexing().createIndexing(actor, second.documentId(), target);
      // File 0 has no indexing task and no processor: its import request fails.
      dispatchImports(authority);
      var claimB = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(imported.taskId(), claimB.jobId());
      refuse(authority, claimB, 50);
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

  @Test
  void cancelStopsQueuedWorkAndRefusesASecondCancelOrARetry() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 100, a, b);
      var failed = batches.upload(actor, id, 0, a);
      var queued = batches.upload(actor, id, 1, b);
      var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertEquals(failed.taskId(), claim.jobId());
      assertTrue(authority.ingestion().failIngestion(claim, "parser_failed"));

      var cancelled = batches.cancel(actor, id);
      assertEquals(1, cancelled.affected());
      assertEquals("cancelled", cancelled.batch().state());
      assertEquals(List.of("resume"), cancelled.batch().availableActions());
      assertEquals(
          "cancelled", authority.ingestion().ingestionStatus(actor, queued.taskId()).state());
      assertTrue(authority.ingestion().claimIngestion(actor.workspaceId()).isEmpty());
      var conflict = assertThrows(ApplicationException.class, () -> batches.cancel(actor, id));
      assertEquals("batch_action_conflict", conflict.code());
      assertThrows(ApplicationException.class, () -> batches.retryFailed(actor, id));

      // Resume queues only the automatic task the cancel stopped; the parse failure stays.
      var resumed = batches.resume(actor, id, null);
      assertEquals(1, resumed.affected());
      assertEquals("processing", resumed.batch().state());
      assertEquals(queued.taskId(), parse(authority, "source-1.txt", b).taskId());
      assertThrows(ApplicationException.class, () -> batches.resume(actor, id, null));
    }
  }
}
