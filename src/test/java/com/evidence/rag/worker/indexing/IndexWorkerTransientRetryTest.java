package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelRequestUsage;
import com.evidence.rag.model.domain.TransientRetry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The actual child retries transient embedding failures inside one bounded, audited attempt. */
class IndexWorkerTransientRetryTest {
  private static final TransientRetry RETRY =
      new TransientRetry(2, Duration.ofMillis(10), Duration.ofSeconds(5));

  private static long embeddings(IndexingTestServer server) {
    return server.requests.stream().filter(r -> r.path().equals("/embeddings")).count();
  }

  private static ProcessTextIndexer indexer(IndexingTestServer server) {
    return new ProcessTextIndexer(
        server.settings().models(), server.settings().projection(), Duration.ofSeconds(15));
  }

  @Test
  void throttledRequestHonoursRetryAfterAndEveryRequestIsCounted() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleEmbeddings.set(1);
      server.retryAfter = "1";
      long started = System.nanoTime();
      assertEquals(
          1,
          indexer.index(server.claim(1), RETRY, Duration.ofSeconds(15)).verified().segmentCount());
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 900);
      assertEquals(2, embeddings(server));
      assertEquals(new ModelRequestUsage(2, 2, true), indexer.modelUsage());
    }
  }

  @Test
  void serverErrorsBackOffUntilTheAllowanceIsSpent() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleStatus = 503;
      server.throttleEmbeddings.set(5);
      var failure =
          assertThrows(
              ProcessTextIndexer.Failure.class,
              () -> indexer.index(server.claim(1), RETRY, Duration.ofSeconds(15)));
      assertEquals("indexing_failed", failure.code());
      assertEquals(3, embeddings(server));
      assertEquals(new ModelRequestUsage(3, 3, true), indexer.modelUsage());
    }
  }

  @Test
  void withoutRetriesOrBeyondTheMaximumWaitTheAttemptFailsOnce() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleEmbeddings.set(1);
      assertThrows(
          ProcessTextIndexer.Failure.class,
          () -> indexer.index(server.claim(1), TransientRetry.NONE, Duration.ofSeconds(15)));
      assertEquals(1, embeddings(server));
    }
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleEmbeddings.set(1);
      server.retryAfter = "120";
      long started = System.nanoTime();
      assertThrows(
          ProcessTextIndexer.Failure.class,
          () -> indexer.index(server.claim(1), RETRY, Duration.ofSeconds(15)));
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10);
      assertEquals(1, embeddings(server));
    }
  }

  @Test
  void aClientErrorIsNotRetried() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer = indexer(server)) {
      server.throttleStatus = 400;
      server.throttleEmbeddings.set(1);
      assertThrows(
          ProcessTextIndexer.Failure.class,
          () -> indexer.index(server.claim(1), RETRY, Duration.ofSeconds(15)));
      assertEquals(1, embeddings(server));
    }
  }
}
