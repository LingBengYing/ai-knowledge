package com.evidence.rag.service;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/** Frozen-target indexing use case; remote execution never holds an authority transaction. */
public final class IndexingTaskProcessor {
  private final IndexingService authority;
  private final String workspace;
  private final IndexTarget target;
  private final Duration timeout;
  private final Function<Duration, ProcessTextIndexer> workers;

  public IndexingTaskProcessor(
      IndexingService authority,
      String workspace,
      OpenAiCompatibleModels.Configuration models,
      MilvusRestProjection.Settings projection,
      IndexTarget target,
      Duration timeout) {
    this(
        authority,
        workspace,
        target,
        timeout,
        deadline -> new ProcessTextIndexer(models, projection, deadline));
  }

  // Real worker and controlled real executable share this package-private test Seam.
  IndexingTaskProcessor(
      IndexingService authority,
      String workspace,
      IndexTarget target,
      Duration timeout,
      Function<Duration, ProcessTextIndexer> workers) {
    this.authority = authority;
    this.workspace = workspace;
    this.target = target;
    this.timeout = timeout;
    this.workers = workers;
  }

  public TaskResult create(Actor actor, String documentId) {
    return authority.createIndexing(actor, documentId, target);
  }

  public TaskResult retry(Actor actor, String taskId) {
    return authority.retryIndexing(actor, taskId, target);
  }

  public Optional<IndexClaim> claim() {
    return authority.claimIndexing(workspace);
  }

  public boolean isCurrent(IndexClaim claim) {
    return authority.isIndexingClaimCurrent(claim);
  }

  public void process(IndexClaim claim) {
    try {
      if (!target.equals(claim.target())) {
        fail(claim, "index_configuration_changed");
        return;
      }
      try (var worker = workers.apply(timeout)) {
        var result = worker.index(claim);
        authority.completeIndexing(claim, result.entryDigests(), result.verified());
      }
    } catch (ProcessTextIndexer.Failure failure) {
      fail(
          claim,
          switch (failure.code()) {
            case "indexing_timeout" -> "indexing_timeout";
            case "indexing_output_invalid" -> "indexing_output_invalid";
            case "indexing_closed",
                "indexing_cancelled",
                "indexing_interrupted",
                "worker_interrupted" ->
                "worker_interrupted";
            default -> "indexing_failed";
          });
    } catch (RuntimeException failure) {
      failUnexpected(claim);
    }
  }

  public void failUnexpected(IndexClaim claim) {
    fail(claim, Thread.currentThread().isInterrupted() ? "worker_interrupted" : "indexing_failed");
  }

  private void fail(IndexClaim claim, String code) {
    if (claim == null) {
      return;
    }
    try {
      authority.failIndexing(claim, code);
    } catch (ApplicationException unavailable) {
      // Startup recovery handles persisted processing state; no upstream cause is logged.
    }
  }
}
