package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IndexWorkerTest {
  @Test
  void realChildEmbedsEverySegmentInSmallBatchesThenVerifiesWholeRevision() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      var claim = server.claim(35);
      var result = indexer.index(claim);
      var expected = new TreeMap<String, String>();
      for (var segment : claim.items()) {
        String physicalId =
            RetrievalProjection.physicalSegmentId(
                claim.projectionGenerationId(), segment.evidenceId());
        expected.put(
            physicalId,
            RetrievalProjection.entryDigest(
                new RetrievalProjection.Entry(
                    physicalId,
                    claim.workspaceId(),
                    claim.documentId(),
                    claim.projectionGenerationId(),
                    segment.recallText(),
                    List.of(0.1, 0.5))));
      }
      assertEquals(expected, result.entryDigests());
      assertEquals(
          new VerifiedRevision(
              claim.target().projectionIdentity(),
              new RetrievalProjection.RevisionManifest(
                      claim.workspaceId(),
                      claim.documentId(),
                      claim.projectionGenerationId(),
                      expected)
                  .sha256(),
              35),
          result.verified());
      var batches =
          server.requests.stream()
              .filter(request -> request.path().equals("/embeddings"))
              .map(request -> request.body().path("input").size())
              .toList();
      assertEquals(35, batches.stream().mapToInt(Integer::intValue).sum());
      assertTrue(batches.stream().allMatch(size -> size >= 1 && size <= 16));
      assertTrue(
          server.requests.stream()
              .noneMatch(
                  request ->
                      request.path().contains("rerank")
                          || request.path().contains("completions")
                          || request.path().contains("search")));
      assertThrows(UnsupportedOperationException.class, () -> result.entryDigests().clear());
      assertEquals("IndexingResult[redacted]", result.toString());
    }
  }

  @Test
  void invalidVectorsPartialWritesAndIncompleteVerificationNeverReturnAReceipt() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      for (String mode :
          List.of(
              "bad-vector",
              "wrong-dimensions",
              "partial-upsert",
              "missing-verification",
              "changed-text")) {
        server.failureMode = mode;
        var failure =
            assertThrows(
                ProcessTextIndexer.Failure.class, () -> indexer.index(server.claim(2)), mode);
        assertEquals("indexing_failed", failure.code(), mode);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("synthetic"));
      }
      server.failureMode = "";
      assertEquals(2, indexer.index(server.claim(2)).verified().segmentCount());
    }
  }

  @Test
  void largeDimensionsReduceTheActualBatchToStayInsideTheResponseBudget() throws Exception {
    try (var server = new IndexingTestServer(8192, 4 * 1024 * 1024);
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(20))) {
      assertEquals(7, indexer.index(server.claim(7)).verified().segmentCount());
      var sizes =
          server.requests.stream()
              .filter(request -> request.path().equals("/embeddings"))
              .map(request -> request.body().path("input").size())
              .toList();
      assertEquals(List.of(3, 3, 1), sizes);
    }
  }

  @Test
  void cancellationDuringARealEmbeddingRequestStopsBeforeUpsertAndAllowsRetry() throws Exception {
    try (var server = new IndexingTestServer()) {
      server.failureMode = "block-embedding";
      var indexer =
          new ProcessTextIndexer(
              server.settings().models(), server.settings().projection(), Duration.ofSeconds(15));
      var task =
          new FutureTask<>(
              () ->
                  assertThrows(
                          ProcessTextIndexer.Failure.class, () -> indexer.index(server.claim(1)))
                      .code());
      Thread.ofVirtual().start(task);
      try {
        assertTrue(server.embeddingStarted.await(5, TimeUnit.SECONDS));
        indexer.close();
        assertEquals("indexing_closed", task.get(3, TimeUnit.SECONDS));
        assertTrue(
            server.requests.stream().noneMatch(request -> request.path().endsWith("/upsert")));
      } finally {
        indexer.close();
        server.releaseEmbedding.countDown();
      }
      server.failureMode = "";
      try (var retry =
          new ProcessTextIndexer(
              server.settings().models(), server.settings().projection(), Duration.ofSeconds(15))) {
        assertEquals(1, retry.index(server.claim(1)).verified().segmentCount());
      }
    }
  }

  @Test
  void wholeTaskDeadlineIncludesMultipleIndividuallyFastHttpCalls() throws Exception {
    try (var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofMillis(1800))) {
      server.responseDelayMillis = 300;
      assertEquals(
          "indexing_timeout",
          assertThrows(ProcessTextIndexer.Failure.class, () -> indexer.index(server.claim(1)))
              .code());
      assertTrue(server.requests.size() >= 2);
      assertTrue(server.requests.stream().noneMatch(request -> request.path().endsWith("/upsert")));
    }
  }

  @Test
  void binaryWorkerInterfaceValidatesTheEntireRequestAndDoesNotMutateJvmOutput() throws Exception {
    try (var server = new IndexingTestServer()) {
      var request =
          IndexProtocol.request(
              server.settings().models(),
              server.settings().projection(),
              Duration.ofSeconds(15),
              server.claim(2));
      var input = new ByteArrayOutputStream();
      IndexProtocol.writeRequest(input, request);
      var output = new ByteArrayOutputStream();
      var stdout = System.out;
      IndexWorker.run(new ByteArrayInputStream(input.toByteArray()), output);
      assertSame(stdout, System.out);
      assertEquals(
          2, IndexProtocol.decode(output.toByteArray(), request).verified().segmentCount());
      byte[] original = input.toByteArray();
      var invalid = new ArrayList<byte[]>();
      invalid.add(new byte[0]);
      invalid.add(Arrays.copyOf(original, original.length - 1));
      invalid.add(Arrays.copyOf(original, original.length + 1));
      invalid.add(new byte[IndexProtocol.MAX_REQUEST + 1]);
      for (int offset : List.of(0, 4, 16)) {
        byte[] bytes = original.clone();
        ByteBuffer.wrap(bytes).putInt(offset, -1);
        invalid.add(bytes);
      }
      byte[] utf8 = original.clone();
      utf8[20] = (byte) 0xff;
      invalid.add(utf8);
      for (byte[] bytes : invalid) {
        output.reset();
        IndexWorker.run(new ByteArrayInputStream(bytes), output);
        assertArrayEquals(IndexProtocol.failure(1), output.toByteArray());
      }
      IndexWorker.run(
          new ByteArrayInputStream(new byte[0]),
          new OutputStream() {
            @Override
            public void write(int value) throws java.io.IOException {
              throw new java.io.IOException("synthetic diagnostic");
            }
          });
    }
  }

  @Test
  void workerRejectsInsufficientResponseBudgetBeforeAnyModelCalls() throws Exception {
    try (var server = new IndexingTestServer(2, 1024)) {
      var request =
          IndexProtocol.request(
              server.settings().models(),
              server.settings().projection(),
              Duration.ofSeconds(15),
              server.claim(1));
      var input = new ByteArrayOutputStream();
      IndexProtocol.writeRequest(input, request);
      var output = new ByteArrayOutputStream();
      IndexWorker.run(new ByteArrayInputStream(input.toByteArray()), output);
      assertArrayEquals(IndexProtocol.failure(1), output.toByteArray());
      assertTrue(server.requests.isEmpty());
    }
  }
}
