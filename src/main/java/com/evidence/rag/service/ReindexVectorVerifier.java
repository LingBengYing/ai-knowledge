package com.evidence.rag.service;

import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.model.domain.ReindexVectorVerification;
import com.evidence.rag.model.domain.VerifiedReindexVectors;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import com.evidence.rag.worker.indexing.VectorReceiptVerificationWorker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact configured media targets and real, complete original-generation verification. */
public final class ReindexVectorVerifier {
  private final Map<String, IndexTarget> targets;
  private final Map<String, MilvusRestProjection.Settings> projections;

  public ReindexVectorVerifier(
      Map<String, IndexTarget> targets, Map<String, MilvusRestProjection.Settings> projections) {
    if (targets == null
        || projections == null
        || !targets.keySet().equals(projections.keySet())
        || !Set.of("image", "audio").containsAll(targets.keySet())) {
      throw ModelValues.invalid();
    }
    this.targets = Map.copyOf(targets);
    this.projections = Map.copyOf(projections);
    for (var route : targets.keySet()) {
      var target = targets.get(route);
      var settings = projections.get(route);
      if (!target.projectionIdentity().equals(settings.identity())
          || !target.embeddingIdentity().equals(settings.embeddingIdentity())
          || !target.embeddingIdentity().equals(target.modelRevision())
          || target.dimensions() != settings.dimension()) {
        throw ModelValues.invalid();
      }
    }
  }

  /** Eligibility only: no network, decoder or model construction. */
  public boolean supports(String route, IndexTarget target) {
    return route != null && target != null && target.equals(targets.get(route));
  }

  public VerifiedReindexVectors verify(ReindexVectorPlan plan, Duration remaining) {
    if (plan == null || remaining == null || remaining.compareTo(Duration.ofMillis(1)) < 0) {
      throw new ProcessTextIndexer.Failure("indexing_timeout");
    }
    long deadline = System.nanoTime() + remaining.toNanos();
    try {
      check(deadline);
      try (var operation =
          LibraryOperationGate.protectCurrent(() -> verifyWithinBudget(plan, deadline))) {
        return operation.call();
      }
    } catch (RuntimeException failure) {
      check(deadline);
      if (failure instanceof ProcessTextIndexer.Failure safe) {
        throw safe;
      }
      throw new ProcessTextIndexer.Failure("indexing_output_invalid");
    } catch (Exception failure) {
      check(deadline);
      throw new ProcessTextIndexer.Failure("indexing_output_invalid");
    }
  }

  private VerifiedReindexVectors verifyWithinBudget(ReindexVectorPlan plan, long deadline) {
    check(deadline);
    var manifests = new HashMap<String, List<RetrievalProjection.RevisionManifest>>();
    manifests.put("image", new ArrayList<>());
    manifests.put("audio", new ArrayList<>());
    // Validate the entire plan before the first projection request. Never silently drop a route.
    for (var binding : plan.images()) {
      check(deadline);
      var origin = binding.origin();
      require(plan, "image", origin.target());
      var manifest =
          new RetrievalProjection.RevisionManifest(
              plan.workspaceId(),
              plan.basePublication().documentId(),
              origin.vectorGenerationId(),
              Map.of(origin.vectorPhysicalSegmentId(), origin.entrySha256()));
      if (!manifest.sha256().equals(origin.manifestSha256())) {
        throw new ProcessTextIndexer.Failure("indexing_output_invalid");
      }
      manifests.get("image").add(manifest);
    }
    for (var binding : plan.audios()) {
      check(deadline);
      var origin = binding.origin();
      require(plan, "audio", origin.target());
      var entries = new HashMap<String, String>();
      for (var entry : origin.entries()) {
        check(deadline);
        if (entries.put(entry.vectorPhysicalSegmentId(), entry.entrySha256()) != null) {
          throw new ProcessTextIndexer.Failure("indexing_output_invalid");
        }
      }
      var manifest =
          new RetrievalProjection.RevisionManifest(
              plan.workspaceId(),
              plan.basePublication().documentId(),
              origin.vectorGenerationId(),
              entries);
      if (!manifest.sha256().equals(origin.manifestSha256())) {
        throw new ProcessTextIndexer.Failure("indexing_output_invalid");
      }
      manifests.get("audio").add(manifest);
    }
    var receipts = new ArrayList<ReindexVectorVerification>();
    for (String route : List.of("image", "audio")) {
      check(deadline);
      if (manifests.get(route).isEmpty()) {
        continue;
      }
      var results =
          VectorReceiptVerificationWorker.verify(
              projections.get(route),
              manifests.get(route),
              Duration.ofNanos(deadline - System.nanoTime()));
      check(deadline);
      for (int index = 0; index < results.size(); index++) {
        String originId =
            route.equals("image")
                ? plan.images().get(index).origin().id()
                : plan.audios().get(index).origin().id();
        receipts.add(
            new ReindexVectorVerification(route, originId, targets.get(route), results.get(index)));
      }
    }
    check(deadline);
    return new VerifiedReindexVectors(plan, receipts);
  }

  private void require(ReindexVectorPlan plan, String route, IndexTarget target) {
    if (!supports(route, target)
        || !plan.workspaceId().equals(projections.get(route).workspaceId())) {
      throw new ProcessTextIndexer.Failure("index_configuration_changed");
    }
  }

  private static void check(long deadline) {
    if (System.nanoTime() >= deadline) {
      throw new ProcessTextIndexer.Failure("indexing_timeout");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new ProcessTextIndexer.Failure("worker_interrupted");
    }
  }
}
