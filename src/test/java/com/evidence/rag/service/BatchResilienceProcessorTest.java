package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.domain.*;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The production processors retry transient conditions within one budgeted attempt. */
class BatchResilienceProcessorTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");
  final byte[] source = "Synthetic resilient batch source".getBytes(StandardCharsets.UTF_8);
  static final TransientRetry RETRY =
      new TransientRetry(2, Duration.ofMillis(10), Duration.ofSeconds(2));

  private String batch(DocumentBatchService batches, int budget) {
    return batches
        .create(
            actor,
            new DocumentBatchCommand(
                "resilience",
                List.of(
                    new DocumentBatchCommand.File(
                        "source.txt", source.length, ModelValues.sha256(source))),
                budget))
        .batchId();
  }

  private IngestionTaskProcessor parser(AuthorityTestContext authority, AtomicInteger calls) {
    return new IngestionTaskProcessor(
        authority.ingestion(),
        actor.workspaceId(),
        Duration.ofSeconds(8),
        limit -> {
          if (calls.getAndIncrement() == 0) throw new TextParser.Failure("parser_busy");
          return new ProcessTextParser(limit);
        });
  }

  @Test
  void aBusyParserIsRetriedInsteadOfFailingTheFile() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String id = batch(batches, 10);
      var file = batches.upload(actor, id, 0, source);
      var calls = new AtomicInteger();
      var processor = parser(authority, calls);
      processor.configureRetry(RETRY);
      processor.process(processor.claim().orElseThrow());
      assertEquals(2, calls.get());
      assertEquals("parsed", authority.ingestion().ingestionStatus(actor, file.taskId()).state());
    }
  }

  @Test
  void anUnconfiguredProcessorStillFailsABusyParserOnce() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String id = batch(batches, 10);
      var file = batches.upload(actor, id, 0, source);
      var calls = new AtomicInteger();
      var processor = parser(authority, calls);
      processor.process(processor.claim().orElseThrow());
      assertEquals(1, calls.get());
      var failed = authority.ingestion().ingestionStatus(actor, file.taskId());
      assertEquals("failed", failed.state());
      // One failed file does not stop the batch.
      assertNull(batches.status(actor, id, 0, 100).haltedReason());
    }
  }

  @Test
  void throttledEmbeddingIsRetriedAndTheBatchIsChargedForEveryRequestSent() throws Exception {
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory)) {
      var batches =
          new DocumentBatchService(
              authority.store(), authority.ingestion(), authority.indexing(), 10000, 2);
      String id = batch(batches, 10);
      var file = batches.upload(actor, id, 0, source);
      var parse = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  parse, new TextParser().parse("source.txt", "text/plain", source)));
      var task = authority.indexing().createIndexing(actor, file.documentId(), external.target());
      var dispatcher = new ImportAutoIndexService(authority.store(), () -> null, null, null);
      while (dispatcher.processNext()) {
        // Adopt the created task.
      }
      var processor =
          new IndexingTaskProcessor(
              authority.indexing(),
              actor.workspaceId(),
              external.target(),
              Duration.ofSeconds(5),
              limit ->
                  new ProcessTextIndexer(
                      external.settings().models(), external.settings().projection(), limit));
      processor.configureResilience(
          RETRY,
          new IndexDeadline(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(30)));
      external.throttleEmbeddings.set(1);
      external.retryAfter = "0";
      processor.process(authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow());

      assertEquals("indexed", authority.indexing().indexingStatus(actor, task.taskId()).state());
      var status = batches.status(actor, id, 0, 100);
      assertEquals("completed", status.state());
      assertEquals(2, status.observedRequests());
      // One expected request plus the one retry used; the unused allowance was released.
      assertEquals(2, status.reservedRequests());
      assertFalse(status.usageUncertain());
      assertNull(status.haltedReason());
    }
  }
}
