package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.ProductHelpCommand;
import com.evidence.rag.tool.retrieval.RetrievalSelection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Internal document/video recall for knowledge answers and the agent; no generation. */
public final class ProductHelpService implements AutoCloseable {
  private static final int MAX_CANDIDATES = 32;
  private final EvidenceService evidence;
  private final ManagedTextRuntime runtime;
  private final Supplier<RetrievalSettings> settings;
  private final long budgetNanos;
  private final AtomicBoolean closed = new AtomicBoolean();

  public ProductHelpService(
      EvidenceService evidence, ManagedTextRuntime runtime, Duration deadline) {
    this(evidence, runtime, deadline, RetrievalSettings::defaults);
  }

  public ProductHelpService(
      EvidenceService evidence,
      ManagedTextRuntime runtime,
      Duration deadline,
      Supplier<RetrievalSettings> settings) {
    if (evidence == null
        || runtime == null
        || settings == null
        || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofSeconds(180)) > 0) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.runtime = runtime;
    this.settings = settings;
    budgetNanos = deadline.toNanos();
  }

  public RetrievalSettings settingsSnapshot() {
    return java.util.Objects.requireNonNull(settings.get());
  }

  /** Internal knowledge-answer retrieval; the caller owns admission and the full query trace. */
  public List<ProductHelpEvidence> retrieve(
      EvidenceScope scope,
      String question,
      TextRuntimeSnapshot snapshot,
      RetrievalSettings retrievalSettings) {
    var progress = new Processing();
    var command =
        new ProductHelpCommand(
            new AnswerCommand(question, scope.selection()), 10, retrievalSettings.rerank());
    finish(scope, List.of(), false, snapshot, progress);
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    var documents = evidence.productHelpPublications(scope, false);
    var videos = evidence.productHelpPublications(scope, true);
    if (documents.isEmpty() && videos.isEmpty()) {
      return List.of();
    }
    stage(
        "retrieval_search_failed",
        snapshot,
        progress,
        () -> {
          snapshot.projection().prepareSearch();
          return null;
        });
    List<Double> vector =
        "full_text".equals(retrievalSettings.searchMethod())
            ? List.of()
            : stage(
                "retrieval_embedding_failed",
                snapshot,
                progress,
                () -> {
                  var result = snapshot.models().embed(List.of(question));
                  if (result == null
                      || result.size() != 1
                      || result.getFirst() == null
                      || result.getFirst().size() != snapshot.target().dimensions()) {
                    throw rejected("retrieval_embedding_failed");
                  }
                  return result.getFirst();
                });
    var documentResult =
        category(scope, documents, false, command, vector, snapshot, progress, retrievalSettings);
    var videoResult =
        category(scope, videos, true, command, vector, snapshot, progress, retrievalSettings);
    if (!finish(scope, documentResult.ids(), false, snapshot, progress)
            .equals(documentResult.material())
        || !finish(scope, videoResult.ids(), true, snapshot, progress)
            .equals(videoResult.material())) {
      throw rejected("evidence_changed");
    }
    var result = new ArrayList<ScoredEvidence>(documentResult.rankedMaterial());
    result.addAll(videoResult.rankedMaterial());
    return RetrievalSelection.select(
            result, retrievalSettings, ScoredEvidence::score, item -> item.source().text())
        .stream()
        .map(ScoredEvidence::source)
        .toList();
  }

  private CategoryResult category(
      EvidenceScope scope,
      List<PublicationVersion> publications,
      boolean video,
      ProductHelpCommand command,
      List<Double> vector,
      TextRuntimeSnapshot snapshot,
      Processing progress,
      RetrievalSettings retrievalSettings) {
    if (publications.isEmpty()) {
      return new CategoryResult(List.of(), List.of(), List.of());
    }
    finish(scope, List.of(), video, snapshot, progress);
    var generations = new LinkedHashMap<String, String>();
    publications.forEach(p -> generations.put(p.documentId(), p.projectionGenerationId()));
    var authorized =
        new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
    var candidates =
        stage(
            "retrieval_search_failed",
            snapshot,
            progress,
            () -> {
              var found =
                  snapshot
                      .projection()
                      .search(
                          query(
                              command.answer().question(), vector, authorized, retrievalSettings));
              if (found == null || found.size() > MAX_CANDIDATES) {
                throw rejected("retrieval_search_failed");
              }
              var seen = new HashSet<String>();
              for (var candidate : found) {
                if (candidate == null
                    || !Double.isFinite(candidate.score())
                    || candidate.score() < 0
                    || !seen.add(candidate.segmentId())) {
                  throw rejected("retrieval_search_failed");
                }
              }
              return List.copyOf(found);
            });
    var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
    var sources = finish(scope, ids, video, snapshot, progress);
    if (sources.isEmpty()) {
      return new CategoryResult(ids, sources, List.of());
    }
    var scores = new HashMap<String, Double>();
    candidates.forEach(candidate -> scores.put(candidate.segmentId(), candidate.score()));
    List<TextModels.Ranked> ranks;
    if (command.rerank()) {
      var material = sources;
      ranks =
          stage(
              "retrieval_rerank_failed",
              snapshot,
              progress,
              () ->
                  ranking(
                      snapshot
                          .models()
                          .rerank(
                              command.answer().question(),
                              material.stream().map(ProductHelpEvidence::text).toList()),
                      material.size()));
    } else {
      var unranked = new ArrayList<TextModels.Ranked>();
      for (int index = 0; index < sources.size(); index++) {
        unranked.add(new TextModels.Ranked(index, scores.get(sources.get(index).physicalId())));
      }
      ranks = ranking(unranked, sources.size());
    }
    var currentSources = finish(scope, ids, video, snapshot, progress);
    if (!currentSources.equals(sources)) {
      throw rejected("evidence_changed");
    }
    return new CategoryResult(
        ids,
        currentSources,
        ranks.stream()
            .map(rank -> new ScoredEvidence(currentSources.get(rank.index()), rank.score()))
            .toList());
  }

  private static RetrievalProjection.Query query(
      String question,
      List<Double> vector,
      RetrievalProjection.AuthorizedScope authorized,
      RetrievalSettings settings) {
    var mode =
        switch (settings.searchMethod()) {
          case "vector" -> RetrievalProjection.SearchMode.DENSE_ONLY;
          case "full_text" -> RetrievalProjection.SearchMode.SPARSE_ONLY;
          default -> RetrievalProjection.SearchMode.HYBRID;
        };
    return new RetrievalProjection.Query(
        question,
        vector,
        authorized,
        MAX_CANDIDATES,
        mode,
        settings.rerank() && "hybrid".equals(settings.searchMethod())
            ? RetrievalProjection.FusionMode.RRF
            : RetrievalProjection.FusionMode.WEIGHTED,
        settings.denseWeight());
  }

  private List<ProductHelpEvidence> finish(
      EvidenceScope scope,
      List<String> ids,
      boolean video,
      TextRuntimeSnapshot snapshot,
      Processing progress) {
    current(snapshot, progress);
    return evidence.finishProductHelp(
        scope,
        ids,
        video,
        snapshot.target(),
        () -> {
          progress.check();
          return runtime.isCurrent(snapshot);
        });
  }

  private void current(TextRuntimeSnapshot snapshot, Processing progress) {
    progress.check();
    if (!runtime.isCurrent(snapshot)) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "configuration_changed", "文字模型配置发生变化，请重新检索。");
    }
  }

  private <T> T stage(
      String code, TextRuntimeSnapshot snapshot, Processing progress, Supplier<T> operation) {
    current(snapshot, progress);
    T value;
    try {
      value = operation.get();
    } catch (RuntimeException failed) {
      current(snapshot, progress);
      throw rejected(code);
    }
    current(snapshot, progress);
    return value;
  }

  private static List<TextModels.Ranked> ranking(List<TextModels.Ranked> ranks, int count) {
    if (ranks == null || ranks.size() != count) {
      throw rejected("retrieval_rerank_failed");
    }
    var seen = new HashSet<Integer>();
    for (var rank : ranks) {
      if (rank == null
          || rank.index() < 0
          || rank.index() >= count
          || !seen.add(rank.index())
          || !Double.isFinite(rank.score())) {
        throw rejected("retrieval_rerank_failed");
      }
    }
    return ranks.stream()
        .sorted(
            Comparator.comparingDouble(TextModels.Ranked::score)
                .reversed()
                .thenComparingInt(TextModels.Ranked::index))
        .toList();
  }

  private static ApplicationException rejected(String code) {
    return new ApplicationException(FailureKind.UNAVAILABLE, code, "资料检索阶段未通过安全校验。");
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(FailureKind.UNAVAILABLE, "retrieval_unavailable", "资料检索暂不可用。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "retrieval_timeout", "资料检索超过处理期限。");
  }

  @Override
  public void close() {
    closed.set(true);
  }

  private record CategoryResult(
      List<String> ids, List<ProductHelpEvidence> material, List<ScoredEvidence> rankedMaterial) {}

  private record ScoredEvidence(ProductHelpEvidence source, double score) {}

  private final class Processing {
    private final long started = System.nanoTime();

    long remaining() {
      return budgetNanos - (System.nanoTime() - started);
    }

    void check() {
      if (Thread.currentThread().isInterrupted() || remaining() <= 0) {
        throw timeout();
      }
      if (closed.get()) {
        throw unavailable();
      }
    }
  }
}
