package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.*;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user restart of a batch file's index task (exhausted attempts or a changed configuration)
 * starts a new task: the batch file follows it, and its first attempt is an explicit user attempt
 * that a batch halt does not cancel.
 */
class DocumentBatchRestartLinkTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");
  final byte[] a = "Synthetic restart source A".getBytes(StandardCharsets.UTF_8);
  final byte[] b = "Synthetic restart source B".getBytes(StandardCharsets.UTF_8);
  final IndexTarget target = new IndexTarget("fixture", "b".repeat(64), "fixture", 2);
  final IndexTarget changed = new IndexTarget("changed", "b".repeat(64), "fixture", 2);

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
    return batches.create(actor, new DocumentBatchCommand("restart-link", files, budget)).batchId();
  }

  private void parse(AuthorityTestContext authority, String name, byte[] source) {
    var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(claim, new TextParser().parse(name, "text/plain", source)));
  }

  private static void dispatchImports(AuthorityTestContext authority) {
    var dispatcher = new ImportAutoIndexService(authority.store(), () -> null, null, null);
    while (dispatcher.processNext()) {
      // Drain every pending import request.
    }
  }

  private static Map<String, String> digests(IndexClaim claim) {
    var digests = new LinkedHashMap<String, String>();
    for (var item : claim.items())
      digests.put(
          RetrievalProjection.physicalSegmentId(claim.projectionGenerationId(), item.evidenceId()),
          "a".repeat(64));
    return Map.copyOf(digests);
  }

  private static VerifiedRevision receipt(IndexClaim claim) {
    var digests = digests(claim);
    return new VerifiedRevision(
        claim.target().projectionIdentity(),
        new RetrievalProjection.RevisionManifest(
                claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests)
            .sha256(),
        digests.size());
  }

  private static DocumentBatchResult.File item(DocumentBatchResult status, int ordinal) {
    return status.items().stream().filter(f -> f.ordinal() == ordinal).findFirst().orElseThrow();
  }

  @Test
  void aRestartOfAnExhaustedTaskIsFollowedByTheBatchFileAndItsSuccessCountsAsIndexed() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 100, a);
      var file = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      String old = authority.indexing().createIndexing(actor, file.documentId(), target).taskId();
      dispatchImports(authority);
      for (int attempt = 1; attempt <= 3; attempt++) {
        if (attempt > 1) authority.indexing().retryIndexing(actor, old, target);
        var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
        assertEquals(old, claim.jobId());
        assertTrue(authority.indexing().failIndexing(claim, "indexing_failed"));
      }
      var exhausted = batches.status(actor, id, 0, 100);
      assertEquals("failed", item(exhausted, 0).state());
      assertEquals(0, exhausted.retryableFiles());

      String fresh = authority.indexing().retryIndexing(actor, old, target).taskId();
      assertNotEquals(old, fresh);
      var restarted = batches.status(actor, id, 0, 100);
      assertEquals(fresh, item(restarted, 0).indexingTaskId());
      assertEquals("indexing", item(restarted, 0).state());
      assertEquals(0, restarted.retryableFiles());

      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(fresh, claim.jobId());
      assertTrue(authority.indexing().reserveBatchRequests(claim, 1));
      assertTrue(authority.indexing().completeIndexing(claim, digests(claim), receipt(claim)));

      var done = batches.status(actor, id, 0, 100);
      assertEquals("indexed", item(done, 0).state());
      assertNull(item(done, 0).errorCode());
      assertEquals(Map.of("indexed", 1L), done.counts());
      assertEquals(0, done.retryableFiles());
      assertFalse(done.availableActions().contains("retry_failed"));
      // The restarted attempt is batch work: its reservation is charged to the batch.
      assertEquals(1, done.reservedRequests());
    }
  }

  @Test
  void aRestartAfterAConfigurationChangeRunsInAHaltedBatchAndAFailureCountsAsFailed() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = batches(authority);
      String id = batch(batches, 2, a, b);
      var first = batches.upload(actor, id, 0, a);
      parse(authority, "source-0.txt", a);
      var second = batches.upload(actor, id, 1, b);
      parse(authority, "source-1.txt", b);
      var taskA = authority.indexing().createIndexing(actor, first.documentId(), target);
      var taskB = authority.indexing().createIndexing(actor, second.documentId(), target);
      dispatchImports(authority);

      var claimA = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(taskA.taskId(), claimA.jobId());
      assertFalse(authority.indexing().reserveBatchRequests(claimA, 50));
      assertTrue(authority.indexing().cancelUnreservedIndexing(claimA));
      assertEquals("halted", batches.status(actor, id, 0, 100).state());
      // The halt cancels B's automatic attempt.
      assertTrue(authority.indexing().claimIndexing(actor.workspaceId()).isEmpty());
      assertEquals("cancelled", authority.indexing().indexingStatus(actor, taskB.taskId()).state());

      // The user retries B under a changed configuration: a fresh task, explicit, not cancelled.
      String fresh = authority.indexing().retryIndexing(actor, taskB.taskId(), changed).taskId();
      assertNotEquals(taskB.taskId(), fresh);
      var linked = batches.status(actor, id, 0, 100);
      assertEquals(fresh, item(linked, 1).indexingTaskId());
      assertEquals("indexing", item(linked, 1).state());
      var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertEquals(fresh, claim.jobId());
      assertEquals(1, claim.attempt());
      assertTrue(authority.indexing().reserveBatchRequests(claim, 1));
      assertTrue(authority.indexing().failIndexing(claim, "indexing_failed"));

      var failed = batches.status(actor, id, 0, 100);
      assertEquals("failed", item(failed, 1).state());
      assertEquals("indexing_failed", item(failed, 1).errorCode());

      // Resume requeues only A's automatic attempt; B's failed explicit task is left alone.
      var resumed = batches.resume(actor, id, 100);
      assertEquals(1, resumed.affected());
      assertEquals(
          "failed", authority.indexing().indexingStatus(actor, fresh).state(), "explicit task");
      assertEquals(
          "queued", authority.indexing().indexingStatus(actor, taskA.taskId()).state(), "resumed");
    }
  }
}
