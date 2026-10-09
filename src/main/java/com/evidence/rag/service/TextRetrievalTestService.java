package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.RetrievalSettingsResult;
import com.evidence.rag.model.dto.RetrievalTestCommand;
import com.evidence.rag.model.dto.RetrievalTestMatch;
import com.evidence.rag.model.dto.RetrievalTestResult;
import com.evidence.rag.tool.retrieval.RetrievalSelection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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

/** Retrieval-only use case: one captured bundle, complete scope, no extraction or trace writes. */
public final class TextRetrievalTestService implements AutoCloseable {
  private static final int MAX_CANDIDATES = 64;
  private final EvidenceService evidence;
  private final ManagedTextRuntime runtime;
  private final Supplier<RetrievalSettings> settings;
  private final long budgetNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<Processing> processing = ConcurrentHashMap.newKeySet();

  public TextRetrievalTestService(
      EvidenceService evidence,
      ManagedTextRuntime runtime,
      Duration deadline,
      int maximumConcurrent) {
    this(evidence, runtime, deadline, maximumConcurrent, RetrievalSettings::defaults);
  }

  public TextRetrievalTestService(
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
              var thread = new Thread(runnable, "text-retrieval-test");
              thread.setDaemon(true);
              return thread;
            });
  }

  public RetrievalTestResult test(Actor actor, RetrievalTestCommand command) {
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
          FailureKind.CAPACITY_EXCEEDED, "retrieval_capacity_exceeded", "检索测试任务已达并发上限。");
    }
    var result = new CompletableFuture<RetrievalTestResult>();
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

  private RetrievalTestResult execute(
      Actor actor, RetrievalTestCommand command, Processing progress) {
    progress.check();
    var retrievalSettings = command.effectiveSettings(settings.get());
    var snapshot = runtime.capture();
    var scope =
        evidence.snapshot(
            actor,
            com.evidence.rag.model.domain.DocumentSelection.allDocuments(),
            snapshot.target());
    current(snapshot, progress);
    var texts = evidence.textPublications(scope);
    finish(scope, List.of(), snapshot, progress);
    String testId = UUID.randomUUID().toString();
    if (scope.publications().isEmpty()) {
      return empty(testId, snapshot, scope, "empty_scope", retrievalSettings);
    }
    // Narrow only search candidates; finish() continues to validate the complete authority scope.
    if (texts.isEmpty()) {
      return empty(testId, snapshot, scope, "no_matches", retrievalSettings);
    }
    stage(
        "retrieval_search_failed",
        snapshot,
        progress,
        () -> {
          snapshot.projection().prepareSearch();
          return null;
        });
    finish(scope, List.of(), snapshot, progress);
    var generations = new LinkedHashMap<String, String>();
    for (var publication : texts) {
      generations.put(publication.documentId(), publication.projectionGenerationId());
    }
    var authorized = new RetrievalProjection.AuthorizedScope(actor.workspaceId(), generations);
    List<Double> vector =
        "full_text".equals(retrievalSettings.searchMethod())
            ? List.of()
            : stage(
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
    var mode =
        switch (retrievalSettings.searchMethod()) {
          case "vector" -> RetrievalProjection.SearchMode.DENSE_ONLY;
          case "full_text" -> RetrievalProjection.SearchMode.SPARSE_ONLY;
          default -> RetrievalProjection.SearchMode.HYBRID;
        };
    var query =
        new RetrievalProjection.Query(
            command.answer().question(),
            vector,
            authorized,
            MAX_CANDIDATES,
            mode,
            retrievalSettings.rerank() && "hybrid".equals(retrievalSettings.searchMethod())
                ? RetrievalProjection.FusionMode.RRF
                : RetrievalProjection.FusionMode.WEIGHTED,
            retrievalSettings.denseWeight());
    finish(scope, List.of(), snapshot, progress);
    var candidates =
        stage(
            "retrieval_search_failed",
            snapshot,
            progress,
            () -> {
              var found = snapshot.projection().search(query);
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
    var sources = finish(scope, ids, snapshot, progress);
    if (sources.isEmpty()) {
      return empty(testId, snapshot, scope, "no_matches", retrievalSettings);
    }
    var retrievalScores = new LinkedHashMap<String, Double>();
    for (var candidate : candidates) {
      retrievalScores.put(candidate.segmentId(), candidate.score());
    }
    List<TextModels.Ranked> ranks;
    if (retrievalSettings.rerank()) {
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
                              material.stream().map(item -> item.segment().text()).toList()),
                      material.size()));
    } else {
      var unranked = new ArrayList<TextModels.Ranked>();
      for (int index = 0; index < sources.size(); index++) {
        unranked.add(
            new TextModels.Ranked(
                index, retrievalScores.get(sources.get(index).physicalSegmentId())));
      }
      ranks =
          unranked.stream()
              .sorted(
                  Comparator.comparingDouble(TextModels.Ranked::score)
                      .reversed()
                      .thenComparingInt(TextModels.Ranked::index))
              .toList();
    }
    var currentSources = finish(scope, ids, snapshot, progress);
    if (!currentSources.equals(sources)) {
      throw rejected("evidence_changed");
    }
    var selected =
        RetrievalSelection.select(
            ranks,
            retrievalSettings,
            TextModels.Ranked::score,
            rank -> currentSources.get(rank.index()).segment().text());
    var matches = new ArrayList<RetrievalTestMatch>();
    for (var rank : selected) {
      var source = currentSources.get(rank.index());
      var publication = source.publication();
      var segment = source.segment();
      matches.add(
          new RetrievalTestMatch(
              matches.size() + 1,
              publication.documentId(),
              publication.sourceRevisionId(),
              source.filename(),
              publication.sourceSha256(),
              publication.parserRevision(),
              segment.page(),
              segment.start(),
              segment.end(),
              segment.text(),
              segment.textSha256(),
              retrievalScores.get(source.physicalSegmentId()),
              retrievalSettings.rerank() ? rank.score() : null));
    }
    progress.check();
    return matches.isEmpty()
        ? empty(testId, snapshot, scope, "no_matches", retrievalSettings)
        : new RetrievalTestResult(
            testId,
            snapshot.version(),
            "completed",
            null,
            scope.publications().size(),
            retrievalSettings.retrievalScoreKind(),
            matches,
            RetrievalSettingsResult.from(retrievalSettings));
  }

  private List<PublishedEvidence> finish(
      EvidenceScope scope, List<String> ids, TextRuntimeSnapshot snapshot, Processing progress) {
    current(snapshot, progress);
    return evidence.finishRetrieval(
        scope,
        ids,
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

  private static RetrievalTestResult empty(
      String id,
      TextRuntimeSnapshot snapshot,
      EvidenceScope scope,
      String reason,
      RetrievalSettings settings) {
    return new RetrievalTestResult(
        id,
        snapshot.version(),
        "empty",
        reason,
        scope.publications().size(),
        settings.retrievalScoreKind(),
        List.of(),
        RetrievalSettingsResult.from(settings));
  }

  private static ApplicationException rejected(String code) {
    return new ApplicationException(FailureKind.UNAVAILABLE, code, "检索测试的模型或投影阶段未通过安全校验。");
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(FailureKind.UNAVAILABLE, "retrieval_unavailable", "检索测试暂不可用。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "retrieval_timeout", "检索测试超过处理期限。");
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      for (var progress : processing) {
        progress.cancel();
      }
      // Queued accepted bodies still execute their finally and release the reserved operation.
      executor.shutdown();
    }
  }

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
