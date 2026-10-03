package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** One complete original-audio vector build in an isolated child JVM. */
public final class AudioVectorWorker {
  private AudioVectorWorker() {}

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
      var request = AudioVectorProtocol.readRequest(input);
      try (var lifetime =
          IndexWorkerLifetime.acquire(
              request.projection(), request.parent(), request.timeout(), isolated)) {
        output.write(AudioVectorProtocol.encode(index(request, lifetime)));
        output.flush();
        return;
      }
    } catch (IOException | RuntimeException failure) {
      try {
        output.write(
            AudioVectorProtocol.failure(
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

  private static AudioVectorReceipt index(
      AudioVectorProtocol.Request request, IndexWorkerLifetime lifetime) {
    var claim = request.claim();
    try (var models = new GeminiAudioEmbeddingModels(request.models());
        var projection = new MilvusRestProjection(request.projection())) {
      lifetime.check();
      projection.initialize();
      var receipts = new ArrayList<AudioVectorReceipt.Entry>();
      var digests = new TreeMap<String, String>();
      for (var span : claim.spans()) {
        lifetime.check();
        var vector = models.embed(span.waveform().wav());
        lifetime.check();
        String id =
            RetrievalProjection.physicalSegmentId(
                claim.vectorGenerationId(), span.audioEvidenceId());
        var entry =
            new RetrievalProjection.Entry(
                id,
                claim.actor().workspaceId(),
                claim.basePublication().documentId(),
                claim.vectorGenerationId(),
                span.waveform().pcmSha256(),
                vector);
        // Keep each remote packet bounded without reducing the admitted 600-span document.
        projection.upsert(List.of(entry));
        lifetime.check();
        String digest = RetrievalProjection.entryDigest(entry);
        digests.put(id, digest);
        receipts.add(new AudioVectorReceipt.Entry(id, entry.vector(), digest));
      }
      var manifest =
          new RetrievalProjection.RevisionManifest(
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              digests);
      var verified = projection.verify(manifest);
      lifetime.check();
      var receipt = new AudioVectorReceipt(receipts, verified);
      AudioVectorProtocol.verify(claim, receipt);
      return receipt;
    }
  }
}
