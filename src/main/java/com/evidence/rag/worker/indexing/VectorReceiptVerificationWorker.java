package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Bounded read-only verification, holding one collection lease at a time on the actual caller. */
public final class VectorReceiptVerificationWorker {
  private VectorReceiptVerificationWorker() {}

  public static List<VerifiedRevision> verify(
      MilvusRestProjection.Settings settings,
      List<RetrievalProjection.RevisionManifest> manifests,
      Duration remaining) {
    if (settings == null
        || manifests == null
        || manifests.isEmpty()
        || remaining == null
        || remaining.compareTo(Duration.ofMillis(1)) < 0) {
      throw new ProcessTextIndexer.Failure("indexing_output_invalid");
    }
    var complete = List.copyOf(manifests);
    long started = System.nanoTime();
    var timeout = settings.timeout().compareTo(remaining) < 0 ? settings.timeout() : remaining;
    var bounded =
        new MilvusRestProjection.Settings(
            settings.endpoint(),
            settings.token(),
            settings.database(),
            settings.collection(),
            settings.workspaceId(),
            settings.embeddingIdentity(),
            settings.dimension(),
            timeout,
            settings.maxResponseBytes(),
            settings.allowLoopbackHttp(),
            settings.analyzer());
    try (var lifetime =
            IndexWorkerLifetime.acquire(
                bounded, IndexWorkerLifetime.Parent.current(), remaining, false);
        var projection = new MilvusRestProjection(bounded)) {
      lifetime.check();
      projection.prepareSearch();
      lifetime.check();
      var verified = new ArrayList<VerifiedRevision>();
      for (var manifest : complete) {
        lifetime.check();
        if (!settings.workspaceId().equals(manifest.workspaceId())) {
          throw new ProcessTextIndexer.Failure("indexing_output_invalid");
        }
        verified.add(projection.verify(manifest));
        lifetime.check();
      }
      return List.copyOf(verified);
    } catch (RuntimeException failure) {
      if (System.nanoTime() - started >= remaining.toNanos()) {
        throw new ProcessTextIndexer.Failure("indexing_timeout");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new ProcessTextIndexer.Failure("worker_interrupted");
      }
      if (failure instanceof ProcessTextIndexer.Failure safe) {
        throw safe;
      }
      throw new ProcessTextIndexer.Failure("indexing_output_invalid");
    }
  }
}
