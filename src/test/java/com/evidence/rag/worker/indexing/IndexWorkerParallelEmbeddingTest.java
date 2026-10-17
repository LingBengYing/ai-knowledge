package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.EmbeddingThroughput;
import com.evidence.rag.model.domain.ModelRequestUsage;
import com.evidence.rag.model.domain.TransientRetry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 #5: the actual child embeds one document with several requests in flight, still writes
 * Milvus strictly in segment order, and keeps the request audit and the retry allowance exact.
 */
class IndexWorkerParallelEmbeddingTest {
  private static final TransientRetry RETRY =
      new TransientRetry(2, Duration.ofMillis(10), Duration.ofSeconds(5));
  private static final Pattern ORDINAL = Pattern.compile("第(\\d+)段");

  private static long embeddings(IndexingTestServer server) {
    return server.requests.stream().filter(r -> r.path().equals("/embeddings")).count();
  }

  private static ProcessTextIndexer indexer(IndexingTestServer server) {
    return new ProcessTextIndexer(
        server.settings().models(), server.settings().projection(), Duration.ofSeconds(30));
  }

  @Test
  void fourRequestsRunInFlightWhileUpsertsStayInSegmentOrderAndUsageIsExact() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.embeddingDelayMillis = 200;
      var claim = server.claim(320);
      int expected = indexer.expectedRequests(claim);
      assertEquals(10, expected, "32 segments per request");
      var result =
          indexer.index(claim, RETRY, Duration.ofSeconds(30), new EmbeddingThroughput(4, 0, 0));
      assertEquals(320, result.verified().segmentCount());
      assertEquals(expected, embeddings(server));
      int peak = server.maxEmbeddingsInFlight.get();
      assertTrue(peak > 1 && peak <= 4, "peak in flight " + peak);
      assertEquals(new ModelRequestUsage(expected, expected, true), indexer.modelUsage());
      var ordinals = new ArrayList<Integer>();
      for (var upsert : server.committedUpserts) {
        for (var row : upsert.body().path("data")) {
          var matcher = ORDINAL.matcher(row.path("text").asString());
          assertTrue(matcher.find());
          ordinals.add(Integer.parseInt(matcher.group(1)));
        }
      }
      assertEquals(320, ordinals.size());
      for (int i = 0; i < ordinals.size(); i++) {
        assertEquals(i, ordinals.get(i), "upserts follow segment order");
      }
    }
  }

  @Test
  void concurrentRetriesShareOneAllowanceAndEveryRequestIsAudited() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleStatus = 503;
      server.throttleEmbeddings.set(2);
      var claim = server.claim(256);
      int expected = indexer.expectedRequests(claim);
      assertEquals(
          256,
          indexer
              .index(claim, RETRY, Duration.ofSeconds(30), new EmbeddingThroughput(4, 0, 0))
              .verified()
              .segmentCount());
      assertEquals(expected + 2, embeddings(server));
      assertEquals(new ModelRequestUsage(expected + 2, expected + 2, true), indexer.modelUsage());
    }
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleStatus = 503;
      server.throttleEmbeddings.set(1000);
      var claim = server.claim(256);
      var failure =
          assertThrows(
              ProcessTextIndexer.Failure.class,
              () ->
                  indexer.index(
                      claim, RETRY, Duration.ofSeconds(30), new EmbeddingThroughput(4, 0, 0)));
      assertEquals("indexing_failed", failure.code());
      long sent = embeddings(server);
      // At most the four first attempts in flight plus the two retries of the shared allowance.
      assertTrue(sent >= 3 && sent <= 6, "sent " + sent);
      var usage = indexer.modelUsage();
      assertEquals(usage.started(), usage.finished());
      assertEquals(sent, usage.started());
      assertTrue(usage.complete());
    }
  }

  @Test
  void theSharedRequestBucketPacesRequestsAcrossTheAttempt() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      var claim = server.claim(160);
      assertEquals(5, indexer.expectedRequests(claim));
      long started = System.nanoTime();
      // 60/min refills one request a second with a two-request burst: 5 requests take ~3 s.
      indexer.index(claim, RETRY, Duration.ofSeconds(30), new EmbeddingThroughput(4, 60, 0));
      long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(elapsed >= 2500, "elapsed " + elapsed);
      assertEquals(5, embeddings(server));
      assertEquals(new ModelRequestUsage(5, 5, true), indexer.modelUsage());
    }
  }
}
