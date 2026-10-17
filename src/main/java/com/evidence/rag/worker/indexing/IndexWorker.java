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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

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
    int dimension = configuration.embeddingDimensions();
    // Incoming model numbers can occupy up to 128 JSON bytes; upsert/readback use float32.
    int modelBatch = (configuration.maxResponseBytes() - 1024) / (dimension * 128 + 1024);
    int projectionBatch =
        (projectionSettings.maxResponseBytes() - 1024)
            / (RetrievalProjection.MAX_TEXT_BYTES * 6 + dimension * 32 + 2048);
    int batchSize = Math.min(16, Math.min(modelBatch, projectionBatch));
    if (batchSize < 1) {
      throw new ProcessTextIndexer.Failure("indexing_failed");
    }
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
      for (int offset = 0; offset < request.items().size(); offset += batchSize) {
        lifetime.check();
        var batch =
            request.items().subList(offset, Math.min(request.items().size(), offset + batchSize));
        var vectors =
            embed(models, batch.stream().map(segment -> segment.recallText()).toList(), retries);
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
        // Only the digest map survives the iteration; complete-revision vectors are never retained.
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
   * Each retried embedding request is a new HTTP request and is counted by the model audit like any
   * other; the parent reserved the whole retry allowance before this child started.
   */
  private static List<List<Double>> embed(
      OpenAiCompatibleModels models, List<String> texts, Retries retries) {
    while (true) {
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

    boolean waitBeforeRetry(Duration retryAfter) {
      if (used >= policy.retries()) {
        return false;
      }
      var wait = policy.delay(used, retryAfter);
      // Keep at least a second for the retried request itself, its write and the verification.
      if (wait == null || wait.plusSeconds(1).compareTo(lifetime.remaining()) > 0) {
        return false;
      }
      used++;
      lifetime.pause(wait);
      return true;
    }
  }
}
