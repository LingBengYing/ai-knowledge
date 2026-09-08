package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.SourceEvidence;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.model.dto.CitationResult;
import com.evidence.rag.model.dto.SourceResult;
import com.evidence.rag.tool.answer.TextGrounding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authorized query orchestration. Only the authority's final trace receipt can release an answer.
 */
public final class AnswerService implements AutoCloseable {
  public static final String PROMPT_REVISION = "java-extractive-answer-v1";
  private static final int MAX_CANDIDATES = 64;
  private static final int MAX_QUOTES = 32;
  private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
  private static final String REFUSAL = "当前授权资料不足以可靠回答该问题。";
  private static final Set<String> GROUNDING_REASONS =
      Set.of(
          "unsupported_question",
          "incomplete_evidence",
          "conflicting_evidence",
          "unsafe_evidence",
          "invalid_quote");
  private final EvidenceService evidence;
  private final TextModels models;
  private final RetrievalProjection projection;
  private final IndexTarget target;
  private final long timeoutNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final TextGrounding grounding = new TextGrounding();

  public AnswerService(
      EvidenceService evidence,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int maximumConcurrent) {
    if (evidence == null
        || models == null
        || projection == null
        || target == null
        || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofMinutes(10)) > 0
        || maximumConcurrent < 1
        || maximumConcurrent > 8
        || !target.modelRevision().equals(models.revision())
        || !target.projectionIdentity().equals(projection.identity())) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.models = models;
    this.projection = projection;
    this.target = target;
    timeoutNanos = deadline.toNanos();
    admission = new Semaphore(maximumConcurrent);
    executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-answer-", 0).factory());
  }

  public AnswerResult answer(Actor actor, AnswerCommand command) {
    if (actor == null || command == null) {
      throw ModelValues.invalid();
    }
    if (closed.get()) {
      throw unavailable();
    }
    var processing = new Processing();
    if (!admission.tryAcquire()) {
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "问答任务已达并发上限。");
    }
    var result = new CompletableFuture<AnswerResult>();
    try {
      // Cancelling the result must not prevent this runnable's finally from releasing admission.
      executor.execute(
          () -> {
            processing.thread.set(Thread.currentThread());
            try {
              result.complete(execute(actor, command, processing));
            } catch (RuntimeException | Error failure) {
              result.completeExceptionally(failure);
            } finally {
              processing.thread.set(null);
              admission.release();
            }
          });
    } catch (RejectedExecutionException failure) {
      admission.release();
      throw unavailable();
    }
    try {
      AnswerResult answer = result.get(Math.max(0, processing.remaining()), TimeUnit.NANOSECONDS);
      processing.check();
      return answer;
    } catch (TimeoutException failure) {
      processing.cancel();
      throw timeout();
    } catch (InterruptedException failure) {
      processing.cancel();
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof ApplicationException application) {
        throw application;
      }
      throw unavailable();
    }
  }

  public SourceResult source(Actor actor, String answerId, int citationOrdinal) {
    if (closed.get()) {
      throw unavailable();
    }
    SourceEvidence source = evidence.source(actor, answerId, citationOrdinal);
    return new SourceResult(answerId, citation(answerId, citationOrdinal, source));
  }

  private AnswerResult execute(Actor actor, AnswerCommand command, Processing processing) {
    processing.check();
    // Invalid explicit selection is not an accepted query, and never falls back to ALL.
    EvidenceScope scope = evidence.snapshot(actor, command.selection(), target);
    Proposal proposed;
    try {
      proposed = propose(scope, command.question(), processing);
    } catch (RejectedEvidence failure) {
      proposed = abstention(command.question(), failure.reason);
    } catch (TextModels.Failure | ProjectionException | CancellationException failure) {
      proposed =
          abstention(
              command.question(),
              processing.active() ? "upstream_unavailable" : "processing_timeout");
    } catch (ApplicationException failure) {
      if ("scope_changed".equals(failure.code())) {
        proposed = abstention(command.question(), "scope_changed");
      } else if (failure.kind() == FailureKind.TIMEOUT || !processing.active()) {
        proposed = abstention(command.question(), "processing_timeout");
      } else if (failure.kind() == FailureKind.INVALID_INPUT
          || failure.kind() == FailureKind.NOT_FOUND) {
        proposed = abstention(command.question(), "upstream_invalid");
      } else if (failure.kind() == FailureKind.CAPACITY_EXCEEDED) {
        proposed = abstention(command.question(), "evidence_capacity_exceeded");
      } else {
        throw failure;
      }
    }
    // Keep audit/storage failures outside the upstream-error catch: no durable trace, no answer.
    var receipt = evidence.finish(scope, proposed.trace(), () -> commitEligibility(processing));
    if (!"answered".equals(receipt.outcome())) {
      return new AnswerResult(
          receipt.traceId(), "abstained", REFUSAL, receipt.reasonCode(), List.of());
    }
    if (!"answered".equals(proposed.trace().outcome())) {
      throw unavailable();
    }
    var citations = new ArrayList<CitationResult>();
    for (int index = 0; index < proposed.sources().size(); index++) {
      citations.add(citation(receipt.traceId(), index + 1, proposed.sources().get(index)));
    }
    return new AnswerResult(receipt.traceId(), "answered", proposed.answer(), null, citations);
  }

  private Proposal propose(EvidenceScope scope, String question, Processing processing) {
    processing.check();
    if (scope.publications().isEmpty()) {
      return abstention(question, "empty_scope");
    }
    requireCurrent(scope, List.of(), processing);
    projection.prepareSearch();
    requireCurrent(scope, List.of(), processing);
    var embedded = models.embed(List.of(question));
    processing.check();
    if (embedded == null
        || embedded.size() != 1
        || embedded.getFirst() == null
        || embedded.getFirst().size() != target.dimensions()) {
      throw rejected("upstream_invalid");
    }
    var generations = new LinkedHashMap<String, String>();
    scope.publications().forEach(p -> generations.put(p.documentId(), p.projectionGenerationId()));
    requireCurrent(scope, List.of(), processing);
    var candidates =
        projection.search(
            new RetrievalProjection.Query(
                question,
                embedded.getFirst(),
                new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations),
                MAX_CANDIDATES));
    processing.check();
    if (candidates == null || candidates.size() > MAX_CANDIDATES) {
      throw rejected("upstream_invalid");
    }
    var retrievalScores = new HashMap<String, Double>();
    for (var candidate : candidates) {
      if (candidate == null
          || !Double.isFinite(candidate.score())
          || retrievalScores.putIfAbsent(candidate.segmentId(), candidate.score()) != null) {
        throw rejected("upstream_invalid");
      }
    }
    if (candidates.isEmpty()) {
      return abstention(question, "no_evidence");
    }
    var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
    List<PublishedEvidence> sources = requireCurrent(scope, ids, processing);
    var rankings =
        models.rerank(question, sources.stream().map(source -> source.segment().text()).toList());
    processing.check();
    var ordered = validatedRanking(rankings, sources.size());
    // Re-read every frozen publication, not just sources that reached the candidate list.
    sources = requireCurrent(scope, ids, processing);
    var rankedEvidence = new ArrayList<TextModels.Evidence>();
    var rerankScores = new HashMap<String, Double>();
    for (var rank : ordered) {
      var source = sources.get(rank.index());
      rankedEvidence.add(
          new TextModels.Evidence(source.physicalSegmentId(), source.segment().text()));
      rerankScores.put(source.physicalSegmentId(), rank.score());
    }
    var extraction = models.extract(question, List.copyOf(rankedEvidence));
    sources = requireCurrent(scope, ids, processing);
    if (extraction == null
        || extraction.quotes().size() > MAX_QUOTES
        || extraction.refused() != extraction.quotes().isEmpty()) {
      throw rejected("upstream_invalid");
    }
    if (extraction.refused()) {
      return abstention(question, "model_refused");
    }
    var quotes = new ArrayList<GroundingQuote>();
    for (var quote : extraction.quotes()) {
      if (quote == null) {
        throw rejected("upstream_invalid");
      }
      quotes.add(new GroundingQuote(quote.evidenceId(), quote.quote()));
    }
    var grounded = grounding.verify(question, sources, quotes);
    processing.check();
    if (!grounded.supported()) {
      return abstention(
          question,
          GROUNDING_REASONS.contains(grounded.reason())
              ? grounded.reason()
              : "incomplete_evidence");
    }
    if (grounded.quotes().isEmpty() || grounded.quotes().size() > MAX_QUOTES) {
      throw rejected("invalid_quote");
    }
    Map<String, PublishedEvidence> byId = new HashMap<>();
    sources.forEach(source -> byId.put(source.physicalSegmentId(), source));
    var trace = new ArrayList<TraceEvidence>();
    var cited = new ArrayList<SourceEvidence>();
    var text = new ArrayList<String>();
    var unique = new HashSet<String>();
    for (var quote : grounded.quotes()) {
      var source = byId.get(quote.physicalId());
      if (source == null
          || quote.start() < source.segment().start()
          || quote.end() > source.segment().end()
          || quote.end() <= quote.start()
          || !unique.add(quote.physicalId() + ":" + quote.start() + ":" + quote.end())) {
        throw rejected("invalid_quote");
      }
      var excerpt = new SourceEvidence(source, quote.start(), quote.end());
      if (!sourceText(excerpt).equals(quote.quote())) {
        throw rejected("invalid_quote");
      }
      trace.add(
          new TraceEvidence(
              trace.size() + 1,
              quote.physicalId(),
              quote.start(),
              quote.end(),
              retrievalScores.get(quote.physicalId()),
              rerankScores.get(quote.physicalId()),
              quote.factHashes()));
      cited.add(excerpt);
      text.add(quote.quote());
    }
    String answer = String.join("\n", text);
    return new Proposal(
        answer,
        List.copyOf(cited),
        new TraceDraft(
            sha(question),
            sha(answer),
            "answered",
            null,
            target.modelRevision(),
            PROMPT_REVISION,
            TextGrounding.VERSION,
            trace));
  }

  private List<PublishedEvidence> requireCurrent(
      EvidenceScope scope, List<String> ids, Processing processing) {
    processing.check();
    if (!configurationCurrent()) {
      throw rejected("configuration_changed");
    }
    var sources = evidence.hydrate(scope, ids);
    processing.check();
    if (sources.size() != ids.size()) {
      throw rejected("upstream_invalid");
    }
    long bytes = 0;
    var pages = new HashSet<String>();
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index);
      if (!ids.get(index).equals(source.physicalSegmentId())) {
        throw rejected("upstream_invalid");
      }
      String key = source.publication().publicationId() + ":" + source.page().number();
      if (pages.add(key)) {
        bytes += source.page().text().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAGE_BYTES) {
          throw rejected("evidence_capacity_exceeded");
        }
      }
    }
    return sources;
  }

  private AnswerEligibility commitEligibility(Processing processing) {
    if (!processing.active()) {
      return AnswerEligibility.PROCESSING_TIMEOUT;
    }
    if (!configurationCurrent()) {
      return AnswerEligibility.CONFIGURATION_CHANGED;
    }
    return processing.active() ? AnswerEligibility.ELIGIBLE : AnswerEligibility.PROCESSING_TIMEOUT;
  }

  private boolean configurationCurrent() {
    return target.modelRevision().equals(models.revision())
        && target.projectionIdentity().equals(projection.identity());
  }

  private static List<TextModels.Ranked> validatedRanking(
      List<TextModels.Ranked> ranks, int count) {
    if (ranks == null || ranks.size() != count) {
      throw rejected("upstream_invalid");
    }
    var seen = new HashSet<Integer>();
    for (var rank : ranks) {
      if (rank == null
          || rank.index() < 0
          || rank.index() >= count
          || !seen.add(rank.index())
          || !Double.isFinite(rank.score())) {
        throw rejected("upstream_invalid");
      }
    }
    return ranks.stream()
        .sorted(
            Comparator.comparingDouble(TextModels.Ranked::score)
                .reversed()
                .thenComparingInt(TextModels.Ranked::index))
        .toList();
  }

  private Proposal abstention(String question, String reason) {
    return new Proposal(
        REFUSAL,
        List.of(),
        new TraceDraft(
            sha(question),
            null,
            "abstained",
            reason,
            target.modelRevision(),
            PROMPT_REVISION,
            TextGrounding.VERSION,
            List.of()));
  }

  private static CitationResult citation(String traceId, int ordinal, SourceEvidence source) {
    var value = source.evidence();
    var publication = value.publication();
    String quote = sourceText(source);
    return new CitationResult(
        ordinal,
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        value.filename(),
        value.segment().page(),
        source.start(),
        source.end(),
        quote,
        sha(quote),
        "/v1/sources/" + traceId + "/" + ordinal);
  }

  private static String sourceText(SourceEvidence source) {
    String page = source.evidence().page().text();
    return page.substring(
        page.offsetByCodePoints(0, source.start()), page.offsetByCodePoints(0, source.end()));
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private record Proposal(String answer, List<SourceEvidence> sources, TraceDraft trace) {
    private Proposal {
      sources = List.copyOf(sources);
    }

    @Override
    public String toString() {
      return "Proposal[redacted]";
    }
  }

  private static RejectedEvidence rejected(String reason) {
    return new RejectedEvidence(reason);
  }

  private static final class RejectedEvidence extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private RejectedEvidence(String reason) {
      super("Evidence rejected", null, false, false);
      this.reason = reason;
    }
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(FailureKind.UNAVAILABLE, "answers_unavailable", "问答服务暂不可用。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "answer_timeout", "问答处理超过时限或已中断。");
  }

  private final class Processing {
    private final long started = System.nanoTime();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    long remaining() {
      return timeoutNanos - (System.nanoTime() - started);
    }

    boolean active() {
      return !cancelled.get()
          && !closed.get()
          && !Thread.currentThread().isInterrupted()
          && remaining() > 0;
    }

    void check() {
      if (!active()) {
        throw timeout();
      }
    }

    void cancel() {
      cancelled.set(true);
      Thread worker = thread.get();
      if (worker != null) {
        worker.interrupt();
      }
    }
  }

  @Override
  public void close() {
    closed.set(true);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new ApplicationException(
            FailureKind.TIMEOUT, "answer_shutdown_timeout", "问答任务尚未退出，关闭未完成。");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ApplicationException(
          FailureKind.TIMEOUT, "answer_shutdown_interrupted", "等待问答任务退出时被中断，关闭未完成。");
    }
  }
}
