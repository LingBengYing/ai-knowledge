package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexingResult;
import com.evidence.rag.model.domain.TransientRetry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Exactly one indexing request per child JVM. */
public final class IndexWorker {
  private IndexWorker() {}

  public static void main(String[] args) {
    var protocol = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    try (var audit = IndexWorkerModelHttpLog.child()) {
      run(System.in, protocol, true);
    }
  }

  /** One bounded request; library logs and exception messages are never protocol output. */
  public static void run(InputStream input, OutputStream output) {
    run(input, output, false);
  }

  private static void run(InputStream input, OutputStream output, boolean isolated) {
    byte[] response;
    try {
      var request = IndexProtocol.readRequest(input);
      try (var lifetime =
          IndexWorkerLifetime.acquireDocument(
              request.projection(),
              request.parent(),
              request.timeout(),
              isolated,
              request.documentId(),
              request.projectionGenerationId())) {
        response = IndexProtocol.encode(index(request, lifetime));
        output.write(response);
        output.flush();
        return;
      }
    } catch (IOException | RuntimeException failure) {
      try {
        response =
            IndexProtocol.failure(
                failure instanceof ProcessTextIndexer.Failure safe
                        && safe.code().equals("indexing_timeout")
                    ? 2
                    : 1);
      } catch (IOException impossible) {
        return;
      }
    }
    try {
      output.write(response);
      output.flush();
    } catch (IOException ignored) {
      // Parent rejects truncation. Never reveal the failed payload on a diagnostics stream.
    }
  }

  private static IndexingResult index(IndexProtocol.Request request, IndexWorkerLifetime lifetime) {
    var configuration = request.models();
    var projectionSettings = request.projection();
    var texts = request.items().stream().map(segment -> segment.recallText()).toList();
    var ranges =
        EmbeddingBatches.plan(
            texts,
            EmbeddingBatches.countLimit(
                configuration.embeddingDimensions(),
                configuration.maxResponseBytes(),
                projectionSettings.maxResponseBytes()));
    var throughput = request.throughput();
    try (var models = new OpenAiCompatibleModels(configuration);
        var projection = new MilvusRestProjection(projectionSettings)) {
      lifetime.check();
      // Only collection creation/load is exclusive. Model requests use generation-fenced rows.
      try (var initialization =
          IndexWorkerLifetime.acquire(
              projectionSettings, request.parent(), request.timeout(), false)) {
        projection.initialize();
      }
      lifetime.check();
      var digests = new TreeMap<String, String>();
      var retries = new Retries(request.retry(), lifetime);
      var limiter = EmbeddingRateLimiter.open(configuration.embedding(), throughput);
      var embeddings =
          new Embeddings(
              models, texts, ranges, retries, limiter, lifetime, throughput.concurrency());
      try {
        for (int index = 0; index < ranges.size(); index++) {
          lifetime.check();
          // Embeddings run ahead concurrently; vectors are written strictly in segment order.
          var vectors = embeddings.next();
          int from = ranges.get(index)[0];
          var batch = request.items().subList(from, ranges.get(index)[1]);
          lifetime.check();
          var entries = new ArrayList<RetrievalProjection.Entry>(batch.size());
          for (int i = 0; i < batch.size(); i++) {
            var segment = batch.get(i);
            entries.add(
                new RetrievalProjection.Entry(
                    RetrievalProjection.physicalSegmentId(
                        request.projectionGenerationId(), segment.evidenceId()),
                    request.workspaceId(),
                    request.documentId(),
                    request.projectionGenerationId(),
                    segment.recallText(),
                    vectors.get(i)));
          }
          lifetime.check();
          projection.upsert(entries);
          lifetime.check();
          for (var entry : entries) {
            digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry));
          }
          // Only the digest map survives the iteration; complete-revision vectors are never
          // retained.
        }
      } finally {
        // Every started request finishes (and is audited) before the audit log closes.
        embeddings.close();
      }
      var manifest =
          new RetrievalProjection.RevisionManifest(
              request.workspaceId(),
              request.documentId(),
              request.projectionGenerationId(),
              digests);
      lifetime.check();
      var verified = projection.verify(manifest);
      lifetime.check();
      return new IndexingResult(digests, verified);
    }
  }

  /**
   * At most {@code concurrency} embedding requests of this document in flight, consumed in order.
   * Vectors of at most that many requests are held at once. A failure stops submitting, interrupts
   * the requests still running (each still logs its finish for the audit) and is rethrown.
   */
  static final class Embeddings implements AutoCloseable {
    private final OpenAiCompatibleModels models;
    private final List<String> texts;
    private final List<int[]> ranges;
    private final Retries retries;
    private final EmbeddingRateLimiter limiter;
    private final IndexWorkerLifetime lifetime;
    private final int concurrency;
    private final ExecutorService pool;
    private final ArrayDeque<Future<List<List<Double>>>> window = new ArrayDeque<>();
    private int submitted;

    Embeddings(
        OpenAiCompatibleModels models,
        List<String> texts,
        List<int[]> ranges,
        Retries retries,
        EmbeddingRateLimiter limiter,
        IndexWorkerLifetime lifetime,
        int concurrency) {
      this.models = models;
      this.texts = texts;
      this.ranges = ranges;
      this.retries = retries;
      this.limiter = limiter;
      this.lifetime = lifetime;
      this.concurrency = concurrency;
      this.pool =
          Executors.newFixedThreadPool(
              concurrency, Thread.ofPlatform().name("index-embed-", 0).daemon(true).factory());
      fill();
    }

    private void fill() {
      while (submitted < ranges.size() && window.size() < concurrency) {
        var range = ranges.get(submitted++);
        var input = texts.subList(range[0], range[1]);
        window.add(pool.submit(() -> embed(models, input, retries, limiter, lifetime)));
      }
    }

    List<List<Double>> next() {
      var head = window.removeFirst();
      try {
        while (true) {
          lifetime.check();
          try {
            var vectors = head.get(25, TimeUnit.MILLISECONDS);
            fill();
            return vectors;
          } catch (TimeoutException pending) {
            // Keep observing the parent, deadline and interrupts while the request runs.
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ProcessTextIndexer.Failure("worker_interrupted");
      } catch (ExecutionException failed) {
        if (failed.getCause() instanceof RuntimeException failure) {
          throw failure;
        }
        throw new ProcessTextIndexer.Failure("indexing_failed");
      } catch (RuntimeException failure) {
        head.cancel(true);
        throw failure;
      }
    }

    @Override
    public void close() {
      pool.shutdownNow();
      boolean interrupted = false;
      // Interrupted requests end promptly; an unconfirmed finish leaves the audit uncertain.
      for (int second = 0; second < 10; second++) {
        try {
          if (pool.awaitTermination(1, TimeUnit.SECONDS)) {
            break;
          }
        } catch (InterruptedException again) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Each retried embedding request is a new HTTP request and is counted by the model audit like any
   * other; the parent reserved the whole retry allowance before this child started.
   */
  private static List<List<Double>> embed(
      OpenAiCompatibleModels models,
      List<String> texts,
      Retries retries,
      EmbeddingRateLimiter limiter,
      IndexWorkerLifetime lifetime) {
    long tokens = limiter == null ? 0 : EmbeddingBatches.estimatedTokens(texts);
    while (true) {
      lifetime.check();
      if (limiter != null) {
        limiter.acquire(tokens, lifetime);
      }
      try {
        return models.embed(texts);
      } catch (TextModels.Failure failure) {
        if (!failure.isTransient() || !retries.waitBeforeRetry(failure.retryAfter())) {
          throw failure;
        }
      }
    }
  }

  /** One shared allowance for the whole attempt; never waits past the request deadline. */
  static final class Retries {
    private final TransientRetry policy;
    private final IndexWorkerLifetime lifetime;
    private int used;

    Retries(TransientRetry policy, IndexWorkerLifetime lifetime) {
      this.policy = policy;
      this.lifetime = lifetime;
    }

    /**
     * Concurrent requests share one allowance: the count is taken atomically, so the attempt never
     * sends more retries than the parent reserved. The wait itself happens outside the lock.
     */
    boolean waitBeforeRetry(Duration retryAfter) {
      Duration wait;
      synchronized (this) {
        if (used >= policy.retries()) {
          return false;
        }
        wait = policy.delay(used, retryAfter);
        // Keep at least a second for the retried request itself, its write and the verification.
        if (wait == null || wait.plusSeconds(1).compareTo(lifetime.remaining()) > 0) {
          return false;
        }
        used++;
      }
      lifetime.pause(wait);
      return true;
    }
  }
}
