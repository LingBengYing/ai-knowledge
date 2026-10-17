package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.config.BatchProcessingSettings;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.*;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentBatchServiceTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");
  final byte[] data = "Synthetic batch source".getBytes(StandardCharsets.UTF_8);
  final BatchProcessingSettings settings = new BatchProcessingSettings(2, 2, 2, 10000);

  DocumentBatchCommand command(int count, int budget) {
    var files =
        java.util.stream.IntStream.range(0, count)
            .mapToObj(
                i ->
                    new DocumentBatchCommand.File(
                        "batch-" + i + ".txt", data.length, ModelValues.sha256(data)))
            .toList();
    return new DocumentBatchCommand("synthetic-batch-request", files, budget);
  }

  @Test
  void concurrentDuplicateUploadAndRestartKeepExactlyOneOriginal() throws Exception {
    String id, document;
    try (var authority = new AuthorityTestContext(directory)) {
      var batches =
          new DocumentBatchService(
              authority.store(), authority.ingestion(), settings.queueCapacity());
      var batch = batches.create(actor, command(2, 10));
      id = batch.batchId();
      assertEquals(id, batches.create(actor, command(2, 10)).batchId());
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var a = executor.submit(() -> batches.upload(actor, id, 0, data));
        var b = executor.submit(() -> batches.upload(actor, id, 0, data));
        document = a.get(5, TimeUnit.SECONDS).documentId();
        assertEquals(document, b.get(5, TimeUnit.SECONDS).documentId());
      }
      assertThrows(ApplicationException.class, () -> batches.upload(actor, id, 1, new byte[] {1}));
      assertEquals(1, batches.status(actor, id, 0, 100).counts().get("queued"));
      assertEquals(1, batches.status(actor, id, 0, 100).counts().get("awaiting_upload"));
      assertThrows(
          ApplicationException.class,
          () -> batches.status(new Actor("other-org", "owner"), id, 0, 100));
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var batches =
          new DocumentBatchService(
              reopened.store(), reopened.ingestion(), settings.queueCapacity());
      assertEquals(document, batches.upload(actor, id, 0, data).documentId());
      assertEquals(2, batches.status(actor, id, 0, 1).total());
      assertEquals(1, batches.status(actor, id, 0, 1).items().size());
      assertEquals(1L, reopened.listDocuments(actor, Map.of()).get("total"));
    }
  }

  @Test
  void budgetReservationIsAtomicAcrossTwoConcurrentClaims() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      authority.indexing().configureConcurrency(2);
      var batches =
          new DocumentBatchService(
              authority.store(), authority.ingestion(), settings.queueCapacity());
      String id = batches.create(actor, command(2, 1)).batchId();
      var target = new IndexTarget("fixture", "b".repeat(64), "fixture", 2);
      for (int i = 0; i < 2; i++) {
        var task = batches.upload(actor, id, i, data);
        var claim = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
        assertTrue(
            authority
                .ingestion()
                .completeIngestion(
                    claim, new TextParser().parse(task.filename(), "text/plain", data)));
        authority.indexing().createIndexing(actor, task.documentId(), target);
      }
      var a = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      var b = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
      assertNotEquals(a.documentId(), b.documentId());
      IndexClaim admitted;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new CountDownLatch(1);
        var first =
            executor.submit(
                () -> {
                  start.await();
                  return authority.indexing().reserveBatchRequests(a, 1);
                });
        var second =
            executor.submit(
                () -> {
                  start.await();
                  return authority.indexing().reserveBatchRequests(b, 1);
                });
        start.countDown();
        boolean aReserved = first.get(5, TimeUnit.SECONDS);
        boolean bReserved = second.get(5, TimeUnit.SECONDS);
        assertNotEquals(
            aReserved, bReserved, "only one simultaneous reservation fits the batch budget");
        admitted = aReserved ? a : b;
      }
      assertEquals("embedding_budget_exhausted", batches.status(actor, id, 0, 100).haltedReason());
      assertEquals(1, batches.status(actor, id, 0, 100).reservedRequests());
      assertTrue(batches.status(actor, id, 0, 100).usageUncertain());
      authority.indexing().recordBatchUsage(admitted, 1, 1, true);
      assertEquals(1, batches.status(actor, id, 0, 100).observedRequests());
      assertFalse(batches.status(actor, id, 0, 100).usageUncertain());
    }
  }

  @Test
  void thousandsOfManifestEntriesAreBatchedAndPagedWithoutCreatingTasks() {
    String id;
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      long started = System.nanoTime();
      var created = batches.create(actor, command(3827, 4000));
      id = created.batchId();
      assertEquals(3827, created.total());
      assertEquals(100, created.items().size());
      assertEquals(3827L, created.counts().get("awaiting_upload"));
      var last = batches.status(actor, id, 3800, 100);
      assertEquals(27, last.items().size());
      assertEquals(3826, last.items().getLast().ordinal());
      assertEquals(0L, authority.listDocuments(actor, Map.of()).get("total"));
      assertEquals(0, created.reservedRequests());
      System.out.println(
          "BATCH_MANIFEST files=3827 elapsed_ms="
              + java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(reopened.store(), reopened.ingestion(), 10000);
      assertEquals(3827, batches.status(actor, id, 3800, 100).total());
    }
  }
}
