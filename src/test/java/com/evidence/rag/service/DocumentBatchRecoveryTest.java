package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.domain.*;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Restart requeue of running batch tasks, bounded by the attempt limit, and retry budgeting. */
class DocumentBatchRecoveryTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");
  final byte[] a = "Synthetic recovery source A".getBytes(StandardCharsets.UTF_8);
  final IndexTarget target = new IndexTarget("fixture", "b".repeat(64), "fixture", 2);

  private static DocumentBatchService batches(AuthorityTestContext authority) {
    return new DocumentBatchService(
        authority.store(), authority.ingestion(), authority.indexing(), 10000, 2);
  }

  private String batch(DocumentBatchService batches, int budget) {
    return batches
        .create(
            actor,
            new DocumentBatchCommand(
                "recovery",
                java.util.List.of(
                    new DocumentBatchCommand.File("source.txt", a.length, ModelValues.sha256(a))),
                budget))
        .batchId();
  }

  /** Uploads and parses the one file and adopts a created index task as its import task. */
  private String indexed(AuthorityTestContext authority, DocumentBatchService batches, String id) {
    var file = batches.upload(actor, id, 0, a);
    var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(claim, new TextParser().parse("source.txt", "text/plain", a)));
    var task = authority.indexing().createIndexing(actor, file.documentId(), target);
    var dispatcher = new ImportAutoIndexService(authority.store(), () -> null, null, null);
    while (dispatcher.processNext()) {
      // Adopt the created task.
    }
    return task.taskId();
  }

  @Test
  void runningImportParsingIsRequeuedOnRestartUntilTheAttemptLimit() {
    String id, task;
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      id = batch(batches, 10);
      task = batches.upload(actor, id, 0, a).taskId();
      assertEquals(
          1, authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow().attempt());
    }
    for (int attempt = 2; attempt <= 3; attempt++) {
      try (var authority = new AuthorityTestContext(directory)) {
        assertEquals("queued", authority.ingestion().ingestionStatus(actor, task).state());
        var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
        assertEquals(task, claim.jobId());
        assertEquals(attempt, claim.attempt());
      }
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var interrupted = authority.ingestion().ingestionStatus(actor, task);
      assertEquals("failed", interrupted.state());
      assertEquals("worker_interrupted", interrupted.errorCode());
      assertNull(batches(authority).status(actor, id, 0, 100).haltedReason());
    }
  }

  @Test
  void restartRequeuedParsingStaysAutomaticAndAStoppedBatchCancelsIt() {
    String id, task;
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      id = batch(batches, 10);
      task = batches.upload(actor, id, 0, a).taskId();
      authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
    }
    try (var authority = new AuthorityTestContext(directory)) {
      // Requeued at attempt 2 as automatic work, which the batch cancel stops; resume takes 3.
      batches(authority).cancel(actor, id);
      assertTrue(authority.ingestion().claimIngestion(actor.workspaceId()).isEmpty());
      assertEquals("cancelled", authority.ingestion().ingestionStatus(actor, task).state());
      assertEquals(1, batches(authority).resume(actor, id, null).affected());
      var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertEquals(3, claim.attempt());
      batches(authority).cancel(actor, id);
    }
    try (var authority = new AuthorityTestContext(directory)) {
      // Running when the batch stopped: restart cancels it so that resume can queue it again.
      assertEquals("cancelled", authority.ingestion().ingestionStatus(actor, task).state());
    }
  }

  @Test
  void runningImportIndexingIsRequeuedOnRestartAndItsReservationStaysCharged() {
    String id, task;
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      id = batch(batches, 100);
      task = indexed(authority, batches, id);
      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(0, authority.indexing().reserveBatchRequests(claim, 10, 0));
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      var status = batches.status(actor, id, 0, 100);
      assertNull(status.haltedReason());
      assertEquals(10, status.reservedRequests());
      assertTrue(status.usageUncertain());
      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(task, claim.jobId());
      assertEquals(2, claim.attempt());
      assertEquals(0, authority.indexing().reserveBatchRequests(claim, 10, 0));
      assertEquals(20, batches.status(actor, id, 0, 100).reservedRequests());
    }
    try (var authority = new AuthorityTestContext(directory)) {
      authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var interrupted = authority.indexing().indexingStatus(actor, task);
      assertEquals("failed", interrupted.state());
      assertEquals("worker_interrupted", interrupted.errorCode());
    }
  }

  @Test
  void retryAllowanceComesOnlyFromSpareBudgetAndTheUnusedPartIsRefunded() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 10);
      indexed(authority, batches, id);
      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(3, authority.indexing().reserveBatchRequests(claim, 4, 3));
      assertEquals(7, batches.status(actor, id, 0, 100).reservedRequests());
      // A second reservation for the same attempt is refused.
      assertEquals(-1, authority.indexing().reserveBatchRequests(claim, 1, 0));
      // Four expected requests plus one retry: the other two allowance requests return.
      authority.indexing().recordBatchUsage(claim, 5, 5, true, 4);
      var status = batches.status(actor, id, 0, 100);
      assertEquals(5, status.reservedRequests());
      assertEquals(5, status.observedRequests());
      assertFalse(status.usageUncertain());
      assertNull(status.haltedReason());
    }
  }

  @Test
  void aTightBudgetGrantsAPartialAllowanceAndAnIncompleteAuditKeepsItAll() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 5);
      indexed(authority, batches, id);
      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(1, authority.indexing().reserveBatchRequests(claim, 4, 3));
      assertEquals(5, batches.status(actor, id, 0, 100).reservedRequests());
      authority.indexing().recordBatchUsage(claim, 3, 2, false, 4);
      var status = batches.status(actor, id, 0, 100);
      assertEquals(5, status.reservedRequests());
      assertEquals("model_usage_uncertain", status.haltedReason());
      assertEquals("halted", status.state());
    }
  }

  @Test
  void anotherAttemptsHeldAllowanceMakesTheNextFileWaitInsteadOfHalting() {
    byte[] b = "Synthetic recovery source B".getBytes(StandardCharsets.UTF_8);
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id =
          batches
              .create(
                  actor,
                  new DocumentBatchCommand(
                      "held",
                      java.util.List.of(
                          new DocumentBatchCommand.File("a.txt", a.length, ModelValues.sha256(a)),
                          new DocumentBatchCommand.File("b.txt", b.length, ModelValues.sha256(b))),
                      2))
              .batchId();
      var tasks = new java.util.ArrayList<String>();
      for (int i = 0; i < 2; i++) {
        byte[] source = i == 0 ? a : b;
        var file = batches.upload(actor, id, i, source);
        var parse = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
        assertTrue(
            authority
                .ingestion()
                .completeIngestion(parse, new TextParser().parse("s.txt", "text/plain", source)));
        tasks.add(authority.indexing().createIndexing(actor, file.documentId(), target).taskId());
      }
      var dispatcher = new ImportAutoIndexService(authority.store(), () -> null, null, null);
      while (dispatcher.processNext()) {
        // Adopt both tasks.
      }
      authority.indexing().configureConcurrency(2);
      var first = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      var second = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(1, authority.indexing().reserveBatchRequests(first, 1, 3, true));
      assertEquals(
          com.evidence.rag.repository.DocumentBatchRepository.HELD,
          authority.indexing().reserveBatchRequests(second, 1, 3, true));
      assertNull(batches.status(actor, id, 0, 100).haltedReason());
      authority.indexing().recordBatchUsage(first, 1, 1, true, 1);
      assertEquals(0, authority.indexing().reserveBatchRequests(second, 1, 3, true));
      assertEquals(2, batches.status(actor, id, 0, 100).reservedRequests());
      assertNull(batches.status(actor, id, 0, 100).haltedReason());
    }
  }
}
