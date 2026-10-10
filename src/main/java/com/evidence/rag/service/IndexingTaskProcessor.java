package com.evidence.rag.service;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/** Frozen-target indexing use case; remote execution never holds an authority transaction. */
public final class IndexingTaskProcessor {
  private final IndexingService authority;
  private final String workspace;
  private final IndexTarget target;

  IndexTarget runtimeTarget() {
    return target;
  }

  private final Duration timeout;
  private final Function<Duration, ProcessTextIndexer> workers;
  private MilvusRestProjection.Settings cleanupProjection;
  private final ReindexVectorVerifier receiptVerifier;

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
    this.cleanupProjection = projection;
  }

  /** Actual continuation graph; receipt-free legacy constructors keep their original contract. */
  public IndexingTaskProcessor(
      IndexingService authority,
      String workspace,
      OpenAiCompatibleModels.Configuration models,
      MilvusRestProjection.Settings projection,
      IndexTarget target,
      Duration timeout,
      ReindexVectorVerifier receiptVerifier) {
    this(
        authority,
        workspace,
        target,
        timeout,
        deadline -> new ProcessTextIndexer(models, projection, deadline),
        Objects.requireNonNull(receiptVerifier));
    this.cleanupProjection = projection;
  }

  // Real worker and controlled real executable share this package-private test Seam.
  IndexingTaskProcessor(
      IndexingService authority,
      String workspace,
      IndexTarget target,
      Duration timeout,
      Function<Duration, ProcessTextIndexer> workers) {
    this(authority, workspace, target, timeout, workers, null);
  }

  private IndexingTaskProcessor(
      IndexingService authority,
      String workspace,
      IndexTarget target,
      Duration timeout,
      Function<Duration, ProcessTextIndexer> workers,
      ReindexVectorVerifier receiptVerifier) {
    this.authority = authority;
    this.workspace = workspace;
    this.target = target;
    this.timeout = timeout;
    this.workers = workers;
    this.receiptVerifier = receiptVerifier;
  }

  public TaskResult create(Actor actor, String documentId) {
    return authority.createIndexing(actor, documentId, target);
  }

  public TaskResult createImported(Actor actor, String documentId, String revisionId) {
    return authority.createIndexing(actor, documentId, revisionId, target);
  }

  public TaskResult reindex(Actor actor, String documentId, String basePublicationId) {
    return authority.createReindexing(actor, documentId, basePublicationId, target);
  }

  public TaskResult replace(
      Actor actor, String documentId, String candidateRevisionId, String baseRevisionId) {
    return authority.createReplacementIndexing(
        actor, documentId, candidateRevisionId, baseRevisionId, target);
  }

  public TaskResult retry(Actor actor, String taskId) {
    return authority.retryIndexing(actor, taskId, target);
  }

  public Optional<IndexClaim> claim() {
    return authority.claimIndexing(workspace);
  }

  public Optional<IndexClaim> claimModelRebuild(String batchId) {
    return authority.claimModelRebuild(workspace, batchId);
  }

  public boolean isCurrent(IndexClaim claim) {
    return authority.isIndexingClaimCurrent(claim);
  }

  public void process(IndexClaim claim) {
    long started = System.nanoTime();
    try {
      if (!target.equals(claim.target())) {
        fail(claim, "index_configuration_changed");
        return;
      }
      if (!authority.isIndexingClaimCurrent(claim)) {
        fail(claim, "indexing_failed");
        return;
      }
      var plan = authority.reindexVectorPlan(claim);
      if (plan.isPresent() && receiptVerifier == null) {
        fail(claim, "index_configuration_changed");
        return;
      }
      if (cleanupProjection != null
          && !authority.registerProjectionWrite(claim, cleanupProjection)) {
        fail(claim, "authorization_changed");
        return;
      }
      try (var worker = workers.apply(plan.isPresent() ? remaining(started) : timeout)) {
        var result = worker.index(claim);
        if (plan.isEmpty()) {
          authority.completeIndexing(claim, result.entryDigests(), result.verified());
        } else {
          if (!authority.isIndexingClaimCurrent(claim)) {
            fail(claim, "indexing_output_invalid");
            return;
          }
          var verified = receiptVerifier.verify(plan.orElseThrow(), remaining(started));
          remaining(started);
          authority.completeIndexing(claim, result.entryDigests(), result.verified(), verified);
        }
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

  /** Analyzer-only rebuild: verify and stream old vectors into a new generation; never embed. */
  public void processReusingVectors(
      IndexClaim claim,
      MilvusRestProjection source,
      RetrievalProjection.RevisionManifest manifest) {
    long started = System.nanoTime();
    try {
      if (cleanupProjection == null
          || source == null
          || manifest == null
          || !target.equals(claim.target())
          || source.identity().equals(target.projectionIdentity())
          || !claim.workspaceId().equals(manifest.workspaceId())
          || !claim.documentId().equals(manifest.documentId())
          || !authority.isIndexingClaimCurrent(claim)) {
        throw new ProcessTextIndexer.Failure("indexing_output_invalid");
      }
      var plan = authority.reindexVectorPlan(claim);
      if (plan.isPresent() && receiptVerifier == null) {
        throw new ProcessTextIndexer.Failure("indexing_output_invalid");
      }
      var items = new HashMap<String, com.evidence.rag.model.domain.ProjectionItem>();
      claim
          .items()
          .forEach(
              item ->
                  items.put(
                      RetrievalProjection.physicalSegmentId(
                          manifest.revisionId(), item.evidenceId()),
                      item));
      if (!items.keySet().equals(manifest.entryDigests().keySet())) {
        throw new ProcessTextIndexer.Failure("indexing_output_invalid");
      }
      source.prepareSearch();
      remaining(started);
      if (!authority.registerProjectionWrite(claim, cleanupProjection)) {
        throw new ProcessTextIndexer.Failure("indexing_output_invalid");
      }
      try (var destination = new MilvusRestProjection(cleanupProjection)) {
        destination.initialize();
        var pending = new ArrayList<RetrievalProjection.Entry>();
        var digests = new TreeMap<String, String>();
        var sourceReceipt =
            source.visitVerifiedEntries(
                manifest,
                entry -> {
                  remaining(started);
                  var item = items.get(entry.segmentId());
                  if (item == null || !item.recallText().equals(entry.text())) {
                    throw new ProcessTextIndexer.Failure("indexing_output_invalid");
                  }
                  var copied =
                      new RetrievalProjection.Entry(
                          RetrievalProjection.physicalSegmentId(
                              claim.projectionGenerationId(), item.evidenceId()),
                          claim.workspaceId(),
                          claim.documentId(),
                          claim.projectionGenerationId(),
                          item.recallText(),
                          entry.vector());
                  if (digests.put(copied.segmentId(), RetrievalProjection.entryDigest(copied))
                      != null) {
                    throw new ProcessTextIndexer.Failure("indexing_output_invalid");
                  }
                  pending.add(copied);
                  if (pending.size() == 16) {
                    destination.upsert(pending);
                    pending.clear();
                  }
                });
        remaining(started);
        if (!sourceReceipt.projectionIdentity().equals(source.identity())
            || !sourceReceipt.manifestSha256().equals(manifest.sha256())
            || sourceReceipt.segmentCount() != claim.items().size()
            || !authority.isIndexingClaimCurrent(claim)) {
          throw new ProcessTextIndexer.Failure("indexing_output_invalid");
        }
        if (!pending.isEmpty()) {
          destination.upsert(pending);
        }
        var copiedManifest =
            new RetrievalProjection.RevisionManifest(
                claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
        var verified = destination.verify(copiedManifest);
        remaining(started);
        if (plan.isPresent()) {
          var vectors = receiptVerifier.verify(plan.orElseThrow(), remaining(started));
          authority.completeIndexing(claim, digests, verified, vectors);
        } else {
          authority.completeIndexing(claim, digests, verified);
        }
      }
    } catch (RuntimeException failure) {
      failUnexpected(claim);
    }
  }

  private Duration remaining(long started) {
    long nanos = timeout.toNanos() - (System.nanoTime() - started);
    if (nanos < Duration.ofMillis(10).toNanos()) {
      throw new ProcessTextIndexer.Failure("indexing_timeout");
    }
    return Duration.ofNanos(nanos);
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
