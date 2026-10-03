package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.model.domain.ProjectionAttempt;
import java.util.List;

/** Cleanup and indexing share the original collection lease inode and bounded admission. */
public final class ProjectionCleanupExecutor {
  private ProjectionCleanupExecutor() {}

  public static ProjectionCleanup.Result clean(
      ProjectionCleanup adapter, List<ProjectionAttempt> attempts) {
    if (!(adapter instanceof MilvusProjectionCleanup configured)) {
      // A deterministic test Adapter supplies its own trusted terminal/resource proof.
      return adapter.clean(attempts);
    }
    if (attempts == null || attempts.isEmpty()) {
      throw new IllegalArgumentException("Registered attempts are required");
    }
    var settings = configured.settingsFor(attempts.getFirst().target());
    if (settings.isEmpty()) {
      return configured.clean(attempts);
    }
    boolean wasInterrupted = Thread.currentThread().isInterrupted();
    try (var lifetime =
        IndexWorkerLifetime.acquire(
            settings.orElseThrow(),
            IndexWorkerLifetime.Parent.current(),
            settings.orElseThrow().timeout(),
            false)) {
      lifetime.check();
      var result = configured.clean(attempts);
      lifetime.check();
      return result;
    } catch (ProcessTextIndexer.Failure unavailable) {
      if (!wasInterrupted && unavailable.code().equals("indexing_timeout")) {
        Thread.interrupted();
      }
      return new ProjectionCleanup.Result(
          "blocked", "blocked", "blocked", "cleanup_projection_busy");
    }
  }
}
