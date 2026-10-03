package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.ImageVectorReceipt;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/** Exactly one original-image vector build in an isolated child JVM. */
public final class ImageVectorWorker {
  private ImageVectorWorker() {}

  public static void main(String[] args) {
    var protocol = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    run(System.in, protocol, true);
  }

  public static void run(InputStream input, OutputStream output) {
    run(input, output, false);
  }

  private static void run(InputStream input, OutputStream output, boolean isolated) {
    try {
      var request = ImageVectorProtocol.readRequest(input);
      try (var lifetime =
          IndexWorkerLifetime.acquire(
              request.projection(), request.parent(), request.timeout(), isolated)) {
        output.write(ImageVectorProtocol.encode(index(request, lifetime)));
        output.flush();
        return;
      }
    } catch (IOException | RuntimeException failure) {
      try {
        output.write(
            ImageVectorProtocol.failure(
                failure instanceof ProcessTextIndexer.Failure safe
                        && safe.code().equals("indexing_timeout")
                    ? 2
                    : 1));
        output.flush();
      } catch (IOException ignored) {
        /* Parent rejects truncation without diagnostics. */
      }
    }
  }

  private static ImageVectorReceipt index(
      ImageVectorProtocol.Request request, IndexWorkerLifetime lifetime) {
    var claim = request.claim();
    try (var models = new SiliconFlowImageEmbeddingModels(request.models());
        var projection = new MilvusRestProjection(request.projection())) {
      lifetime.check();
      var vector = models.embed(claim.original());
      lifetime.check();
      projection.initialize();
      lifetime.check();
      String id =
          RetrievalProjection.physicalSegmentId(
              claim.vectorGenerationId(), claim.imageEvidenceId());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              claim.basePublication().sourceSha256(),
              vector);
      projection.upsert(List.of(entry));
      lifetime.check();
      String digest = RetrievalProjection.entryDigest(entry);
      var manifest =
          new RetrievalProjection.RevisionManifest(
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              Map.of(id, digest));
      var verified = projection.verify(manifest);
      lifetime.check();
      var receipt = new ImageVectorReceipt(id, entry.vector(), digest, verified);
      ImageVectorProtocol.verify(claim, receipt);
      return receipt;
    }
  }
}
