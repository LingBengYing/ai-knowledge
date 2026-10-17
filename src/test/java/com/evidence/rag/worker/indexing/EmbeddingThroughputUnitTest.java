package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.EmbeddingThroughput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 2 #5: request planning bounds and the shared token bucket, with an explicit clock. */
class EmbeddingThroughputUnitTest {
  @TempDir Path directory;

  @Test
  void requestSizeIsTheProviderMaximumBoundedByResponseAndProjectionSizes() {
    assertEquals(32, EmbeddingBatches.countLimit(2, 4 * 1024 * 1024, 4 * 1024 * 1024));
    // 1024 float vectors at 4 MiB: the response bound (31) is below the provider's 32.
    assertEquals(31, EmbeddingBatches.countLimit(1024, 4 * 1024 * 1024, 4 * 1024 * 1024));
    assertEquals(7, EmbeddingBatches.countLimit(1024, 1024 * 1024, 4 * 1024 * 1024));
    assertThrows(ProcessTextIndexer.Failure.class, () -> EmbeddingBatches.plan(List.of("x"), 0));
  }

  @Test
  void planKeepsSegmentOrderAndSplitsOnCountAndRequestBytes() {
    var texts = new ArrayList<String>(Collections.nCopies(70, "短文本"));
    var ranges = EmbeddingBatches.plan(texts, 32);
    assertEquals(3, ranges.size());
    assertEquals(List.of(0, 32, 32, 64, 64, 70), flatten(ranges));
    // Large segments split by the request-byte bound before the count bound.
    String big = "汉".repeat(20000);
    var large = new ArrayList<String>(Collections.nCopies(40, big));
    var split = EmbeddingBatches.plan(large, 32);
    int previous = 0;
    for (var range : split) {
      assertEquals(previous, range[0]);
      long bytes = 0;
      for (int i = range[0]; i < range[1]; i++) bytes += EmbeddingBatches.jsonBytes(large.get(i));
      assertTrue(bytes <= EmbeddingBatches.MAX_INPUT_JSON_BYTES);
      assertTrue(range[1] - range[0] <= 32);
      previous = range[1];
    }
    assertEquals(40, previous);
    assertTrue(split.size() > 2);
    assertEquals(2, EmbeddingBatches.estimatedTokens(List.of("汉汉")));
    assertEquals(1, EmbeddingBatches.estimatedTokens(List.of("")));
  }

  @Test
  void bucketAllowsABurstThenPacesRequestsAndTokens() throws Exception {
    var now = new AtomicLong(1_000_000);
    var limiter =
        EmbeddingRateLimiter.at(
            directory.resolve("bucket"), new EmbeddingThroughput(4, 60, 0), now::get);
    assertEquals(0, limiter.tryTake(10));
    assertEquals(0, limiter.tryTake(10));
    long wait = limiter.tryTake(10);
    assertTrue(wait > 0 && wait <= 1000, "wait " + wait);
    now.addAndGet(wait);
    assertEquals(0, limiter.tryTake(10));
    assertTrue(limiter.tryTake(10) > 0);
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("bucket"))));

    var tokens =
        EmbeddingRateLimiter.at(
            directory.resolve("tokens"), new EmbeddingThroughput(4, 0, 6000), now::get);
    // 6000/min: 100 tokens a second, capacity 200 tokens.
    assertEquals(0, tokens.tryTake(150));
    long tokenWait = tokens.tryTake(150);
    assertTrue(tokenWait >= 1000 && tokenWait <= 1001, "wait " + tokenWait);
    now.addAndGet(tokenWait);
    assertEquals(0, tokens.tryTake(150));
    // A request larger than the capacity waits for a full bucket, then borrows.
    long full = tokens.tryTake(1000);
    assertTrue(full > 0);
    now.addAndGet(full);
    assertEquals(0, tokens.tryTake(1000));
    assertTrue(tokens.tryTake(1) >= 8000);
  }

  @Test
  void twoLimitersOnOneFileShareTheBucketAndATamperedFileFailsClosed() throws Exception {
    var now = new AtomicLong(5_000);
    var file = directory.resolve("shared");
    var a = EmbeddingRateLimiter.at(file, new EmbeddingThroughput(2, 30, 0), now::get);
    var b = EmbeddingRateLimiter.at(file, new EmbeddingThroughput(2, 30, 0), now::get);
    assertEquals(0, a.tryTake(1));
    assertTrue(b.tryTake(1) > 0, "capacity 1 is shared, not per limiter");
    Files.write(file, new byte[] {1, 2, 3});
    var failure = assertThrows(ProcessTextIndexer.Failure.class, () -> a.tryTake(1));
    assertEquals("indexing_lifetime_failed", failure.code());
    Files.delete(file);
    Files.createFile(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(ProcessTextIndexer.Failure.class, () -> a.tryTake(1));
  }

  @Test
  void throughputBoundsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> new EmbeddingThroughput(0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new EmbeddingThroughput(9, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new EmbeddingThroughput(1, -1, 0));
    assertTrue(new EmbeddingThroughput(1, 0, 1).limited());
    assertTrue(!EmbeddingThroughput.SERIAL.limited());
  }

  @Test
  void theThroughputCrossesTheChildProtocolIntact() throws Exception {
    try (var server = new IndexingTestServer()) {
      var request =
          IndexProtocol.request(
              server.settings().models(),
              server.settings().projection(),
              java.time.Duration.ofSeconds(10),
              server.claim(1),
              com.evidence.rag.model.domain.TransientRetry.NONE,
              new EmbeddingThroughput(4, 1000, 400000));
      var bytes = new java.io.ByteArrayOutputStream();
      IndexProtocol.writeRequest(bytes, request);
      var actual = IndexProtocol.readRequest(new java.io.ByteArrayInputStream(bytes.toByteArray()));
      assertEquals(new EmbeddingThroughput(4, 1000, 400000), actual.throughput());
      assertEquals(
          EmbeddingThroughput.SERIAL,
          IndexProtocol.request(
                  server.settings().models(),
                  server.settings().projection(),
                  java.time.Duration.ofSeconds(10),
                  server.claim(1))
              .throughput());
    }
  }

  private static List<Integer> flatten(List<int[]> ranges) {
    var values = new ArrayList<Integer>();
    for (var range : ranges) {
      values.add(range[0]);
      values.add(range[1]);
    }
    return values;
  }
}
