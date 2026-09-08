package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexingResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.TreeMap;

/** Exactly one indexing request per child JVM. */
public final class IndexWorker {
  private IndexWorker() {}

  public static void main(String[] args) {
    var protocol = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    run(System.in, protocol, true);
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
          IndexWorkerLifetime.acquire(
              request.projection(), request.parent(), request.timeout(), isolated)) {
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
      projection.initialize();
      lifetime.check();
      var digests = new TreeMap<String, String>();
      for (int offset = 0; offset < request.segments().size(); offset += batchSize) {
        lifetime.check();
        var batch =
            request
                .segments()
                .subList(offset, Math.min(request.segments().size(), offset + batchSize));
        var vectors = models.embed(batch.stream().map(segment -> segment.text()).toList());
        lifetime.check();
        var entries = new ArrayList<RetrievalProjection.Entry>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
          var segment = batch.get(i);
          entries.add(
              new RetrievalProjection.Entry(
                  RetrievalProjection.physicalSegmentId(
                      request.projectionGenerationId(), segment.segmentId()),
                  request.workspaceId(),
                  request.documentId(),
                  request.projectionGenerationId(),
                  segment.text(),
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
}
