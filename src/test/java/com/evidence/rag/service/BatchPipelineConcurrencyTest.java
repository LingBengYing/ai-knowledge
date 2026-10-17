package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.job.*;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchPipelineConcurrencyTest {
  @TempDir Path directory;

  @Test
  void twoDocumentsReachEmbeddingBeforeEitherIsReleasedAndPublishIndependently() throws Exception {
    var actor = new Actor("org-main", "owner");
    String batchId;
    try (var authority = new AuthorityTestContext(directory);
        var server = new IndexingTestServer()) {
      authority.ingestion().configureQueueCapacity(10000);
      authority.indexing().configureConcurrency(2);
      byte[] bytes = "Synthetic concurrent pipeline".getBytes(StandardCharsets.UTF_8);
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      batchId =
          batches
              .create(
                  actor,
                  new DocumentBatchCommand(
                      "pipeline",
                      java.util.stream.IntStream.range(0, 6)
                          .mapToObj(
                              i ->
                                  new DocumentBatchCommand.File(
                                      "source-" + i + ".txt",
                                      bytes.length,
                                      ModelValues.sha256(bytes)))
                          .toList(),
                      6,
                      // Same bytes on purpose: concurrency, not dedupe, is under test.
                      true))
              .batchId();
      for (int i = 0; i < 6; i++) batches.upload(actor, batchId, i, bytes);
      var parsing =
          new IngestionTaskProcessor(
              authority.ingestion(),
              actor.workspaceId(),
              Duration.ofSeconds(15),
              ProcessTextParser::new);
      var indexing =
          new IndexingTaskProcessor(
              authority.indexing(),
              actor.workspaceId(),
              server.settings().models(),
              server.settings().projection(),
              server.target(),
              Duration.ofSeconds(20));
      server.failureMode = "block-embedding";
      long start = System.nanoTime();
      try (var parserJob = new IngestionJob(parsing, authority.store().operationGate(), 2);
          var autoJob =
              new ImportAutoIndexJob(
                  new ImportAutoIndexService(authority.store(), () -> indexing, null, null));
          var indexJob = new IndexingJob(indexing, authority.store().operationGate(), 2)) {
        try {
          await(
              () ->
                  server.requests.stream().filter(r -> r.path().equals("/embeddings")).count() == 2,
              15);
          assertEquals(
              2,
              server.requests.stream().filter(r -> r.path().equals("/embeddings")).count(),
              "both model requests must overlap, not merely two queued tasks");
          Thread.sleep(300);
          assertEquals(
              2,
              server.requests.stream().filter(r -> r.path().equals("/embeddings")).count(),
              "queued work must not create more than the configured two model callers");
          assertEquals(2, batches.status(actor, batchId, 0, 100).reservedRequests());
        } finally {
          server.releaseEmbedding.countDown();
        }
        await(() -> batches.status(actor, batchId, 0, 100).state().equals("completed"), 20);
        var result = batches.status(actor, batchId, 0, 100);
        assertEquals(6, result.observedRequests());
        assertFalse(result.usageUncertain());
        assertEquals(6, result.counts().get("indexed"));
        System.out.println(
            "BATCH_PIPELINE documents=6 concurrent_embedding=2 observed_requests=6 elapsed_ms="
                + Duration.ofNanos(System.nanoTime() - start).toMillis());
      }
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(reopened.store(), reopened.ingestion(), 10000);
      assertEquals("completed", batches.status(actor, batchId, 0, 100).state());
      assertEquals(6, batches.status(actor, batchId, 0, 100).observedRequests());
    }
  }

  static void await(java.util.function.BooleanSupplier predicate, int seconds) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (!predicate.getAsBoolean()) {
      assertTrue(System.nanoTime() < until, "pipeline deadline");
      Thread.sleep(50);
    }
  }
}
