package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.ProductHelpCommand;
import com.evidence.rag.model.dto.ProductHelpMatch;
import com.evidence.rag.model.dto.ProductHelpResult;
import com.evidence.rag.tool.retrieval.RetrievalSelection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Product-use search: independent document/video recall, original excerpts, no generation. */
public final class ProductHelpService implements AutoCloseable {
  private static final int MAX_CANDIDATES = 32;
  private final EvidenceService evidence;
  private final ManagedTextRuntime runtime;
  private final Supplier<RetrievalSettings> settings;
  private final long budgetNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<Processing> processing = ConcurrentHashMap.newKeySet();

  public ProductHelpService(
      EvidenceService evidence,
      ManagedTextRuntime runtime,
      Duration deadline,
      int maximumConcurrent) {
    this(evidence, runtime, deadline, maximumConcurrent, RetrievalSettings::defaults);
  }

  public ProductHelpService(
      EvidenceService evidence,
      ManagedTextRuntime runtime,
      Duration deadline,
      int maximumConcurrent,
      Supplier<RetrievalSettings> settings) {
    if (evidence == null
        || runtime == null
        || settings == null
        || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofSeconds(180)) > 0
        || maximumConcurrent < 1
        || maximumConcurrent > 8) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.runtime = runtime;
    this.settings = settings;
    budgetNanos = deadline.toNanos();
    admission = new Semaphore(maximumConcurrent);
    executor =
        Executors.newFixedThreadPool(
            maximumConcurrent,
            runnable -> {
              var thread = new Thread(runnable, "product-help-search");
              thread.setDaemon(true);
              return thread;
            });
  }

  public ProductHelpResult search(Actor actor, ProductHelpCommand command) {
    if (actor == null || command == null) {
      throw ModelValues.invalid();
    }
    if (closed.get()) {
      throw unavailable();
    }
    var progress = new Processing();
    var reservation = evidence.operationGate().reserve();
    if (!admission.tryAcquire()) {
      reservation.close();
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "retrieval_capacity_exceeded", "资料检索任务已达并发上限。");
    }
    var result = new CompletableFuture<ProductHelpResult>();
    processing.add(progress);
    try {
      executor.execute(
          () -> {
            try (var operation = reservation.begin()) {
              progress.thread.set(Thread.currentThread());
              result.complete(execute(actor, command, progress));
            } catch (RuntimeException | Error failure) {
              result.completeExceptionally(failure);
            } finally {
              progress.thread.set(null);
              reservation.close();
              processing.remove(progress);
              admission.release();
            }
          });
    } catch (RejectedExecutionException rejected) {
      reservation.close();
      processing.remove(progress);
      admission.release();
      throw unavailable();
    } catch (RuntimeException | Error rejected) {
      reservation.close();
      processing.remove(progress);
      admission.release();
      throw rejected;
    }
    try {
      var completed = result.get(Math.max(0, progress.remaining()), TimeUnit.NANOSECONDS);
      progress.check();
      return completed;
    } catch (TimeoutException expired) {
      progress.cancel();
      throw timeout();
    } catch (InterruptedException interrupted) {
      progress.cancel();
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof ApplicationException application) {
        throw application;
      }
      throw unavailable();
    }
  }

  /** Internal knowledge-answer retrieval; the caller owns admission and the full query trace. */
  public List<ProductHelpEvidence> retrieve(
      EvidenceScope scope, String question, TextRuntimeSnapshot snapshot) {
    return retrieve(scope, question, snapshot, settingsSnapshot());
  }

  public RetrievalSettings settingsSnapshot() {
    return java.util.Objects.requireNonNull(settings.get());
  }

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

  private ProductHelpResult execute(Actor actor, ProductHelpCommand command, Processing progress) {
    progress.check();
    var snapshot = runtime.capture();
    var scope =
        evidence.snapshot(
            actor,
            com.evidence.rag.model.domain.DocumentSelection.allDocuments(),
            snapshot.target());
    finish(scope, List.of(), false, snapshot, progress);
    String searchId = UUID.randomUUID().toString();
    if (scope.publications().isEmpty()) {
      return empty(searchId, snapshot, scope, "empty_scope");
    }
    var documents = evidence.productHelpPublications(scope, false);
    var videos = evidence.productHelpPublications(scope, true);
    finish(scope, List.of(), false, snapshot, progress);
    if (documents.isEmpty() && videos.isEmpty()) {
      return empty(searchId, snapshot, scope, "no_matches");
    }
    stage(
        "retrieval_search_failed",
        snapshot,
        progress,
        () -> {
          snapshot.projection().prepareSearch();
          return null;
        });
    finish(scope, List.of(), false, snapshot, progress);
    var vector =
        stage(
            "retrieval_embedding_failed",
            snapshot,
            progress,
            () -> {
              var vectors = snapshot.models().embed(List.of(command.answer().question()));
              if (vectors == null
                  || vectors.size() != 1
                  || vectors.getFirst() == null
                  || vectors.getFirst().size() != snapshot.target().dimensions()) {
                throw rejected("retrieval_embedding_failed");
              }
              return vectors.getFirst();
            });
    finish(scope, List.of(), false, snapshot, progress);
    var documentResult = category(scope, documents, false, command, vector, snapshot, progress);
    var videoResult = category(scope, videos, true, command, vector, snapshot, progress);
    // The first category must still be valid after the second category's remote work.
    if (!finish(scope, documentResult.ids(), false, snapshot, progress)
            .equals(documentResult.material())
        || !finish(scope, videoResult.ids(), true, snapshot, progress)
            .equals(videoResult.material())) {
      throw rejected("evidence_changed");
    }
    var matches = new ArrayList<ProductHelpMatch>(documentResult.matches());
    matches.addAll(videoResult.matches());
    progress.check();
    return matches.isEmpty()
        ? empty(searchId, snapshot, scope, "no_matches")
        : new ProductHelpResult(
            searchId,
            snapshot.version(),
            "completed",
            null,
            scope.publications().size(),
            "rrf",
            matches);
  }

  private CategoryResult category(
      EvidenceScope scope,
      List<PublicationVersion> publications,
      boolean video,
      ProductHelpCommand command,
      List<Double> vector,
      TextRuntimeSnapshot snapshot,
      Processing progress) {
    return category(scope, publications, video, command, vector, snapshot, progress, null);
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
      return new CategoryResult(List.of(), List.of(), List.of(), List.of());
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
      return new CategoryResult(List.of(), ids, sources, List.of());
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
    var matches = new ArrayList<ProductHelpMatch>();
    for (var rank : ranks.subList(0, Math.min(command.topK(), ranks.size()))) {
      var source = currentSources.get(rank.index());
      matches.add(
          ProductHelpMatch.from(
              matches.size() + 1,
              source,
              scores.get(source.physicalId()),
              command.rerank() ? rank.score() : null));
    }
    return new CategoryResult(
        List.copyOf(matches),
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
    if (settings == null) {
      return new RetrievalProjection.Query(
          question, vector, authorized, MAX_CANDIDATES, RetrievalProjection.SearchMode.HYBRID);
    }
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

  private static ProductHelpResult empty(
      String id, TextRuntimeSnapshot snapshot, EvidenceScope scope, String reason) {
    return new ProductHelpResult(
        id, snapshot.version(), "empty", reason, scope.publications().size(), "rrf", List.of());
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
    if (closed.compareAndSet(false, true)) {
      processing.forEach(Processing::cancel);
      executor.shutdown();
    }
  }

  private record CategoryResult(
      List<ProductHelpMatch> matches,
      List<String> ids,
      List<ProductHelpEvidence> material,
      List<ScoredEvidence> rankedMaterial) {}

  private record ScoredEvidence(ProductHelpEvidence source, double score) {}

  private final class Processing {
    private final long started = System.nanoTime();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    long remaining() {
      return budgetNanos - (System.nanoTime() - started);
    }

    void check() {
      if (cancelled.get() || Thread.currentThread().isInterrupted() || remaining() <= 0) {
        throw timeout();
      }
      if (closed.get()) {
        throw unavailable();
      }
    }

    void cancel() {
      cancelled.set(true);
      var running = thread.get();
      if (running != null) {
        running.interrupt();
      }
    }
  }
}
