package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageVectorScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.PublishedImageEvidence;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.QueryTrace;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualTraceEvidence;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AttachmentAnswerResult;
import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.model.dto.VisualCitationResult;
import com.evidence.rag.model.dto.VisualSourceResult;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

/** Pure-image answer use case; complete library authority remains in EvidenceService. */
public final class VisualAnswerService implements AutoCloseable {
  public static final String PROMPT_REVISION = "java-visual-answer-v1";
  private static final String REFUSAL = "当前授权图片不足以可靠回答该问题。";
  private final EvidenceService evidence;
  private final TextModels text;
  private final VisionModels vision;
  private final RetrievalProjection projection;
  private final IndexTarget target;
  private final String textRevision;
  private final String visualRevision;
  private final QueryAttachmentService queries;
  private final long timeoutNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();

  public VisualAnswerService(
      EvidenceService evidence,
      TextModels text,
      VisionModels vision,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int concurrency) {
    this(evidence, text, vision, projection, target, deadline, concurrency, null);
  }

  public VisualAnswerService(
      EvidenceService evidence,
      TextModels text,
      VisionModels vision,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int concurrency,
      QueryAttachmentService queries) {
    this(evidence, text, vision, projection, target, deadline, concurrency, queries, null);
  }

  private VisualAnswerService(
      EvidenceService evidence,
      TextModels text,
      VisionModels vision,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int concurrency,
      QueryAttachmentService queries,
      TextIndexAnchor anchor) {
    if (evidence == null
        || text == null
        || vision == null
        || projection == null
        || target == null
        || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofMinutes(10)) > 0
        || concurrency < 1
        || concurrency > 8
        || (anchor == null
            ? !target.modelRevision().equals(text.revision())
            : !target.equals(anchor.target()))
        || !target.projectionIdentity().equals(projection.identity())) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.text = text;
    this.vision = vision;
    this.projection = projection;
    this.target = target;
    this.textRevision = ModelValues.identifier(text.revision(), 200);
    this.visualRevision = ModelValues.identifier(vision.revision(), 160);
    this.queries = queries;
    timeoutNanos = deadline.toNanos();
    admission = new Semaphore(concurrency);
    executor =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("rag-visual-answer-", 0).factory());
  }

  /** Reuse the configured visual model while retrieval follows the active text collection. */
  public static VisualAnswerService managed(
      EvidenceService evidence,
      TextModels text,
      VisionModels vision,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int concurrency,
      QueryAttachmentService queries,
      TextIndexAnchor anchor) {
    if (anchor == null) {
      throw ModelValues.invalid();
    }
    return new VisualAnswerService(
        evidence, text, vision, projection, target, deadline, concurrency, queries, anchor);
  }

  public VisualAnswerResult answer(Actor actor, AnswerCommand command) {
    return (VisualAnswerResult) submit(actor, command, List.of()).result();
  }

  public AttachmentAnswerResult answerAttached(
      Actor actor, AnswerCommand command, List<QueryAttachment> attachments) {
    return submit(actor, command, attachments);
  }

  private AttachmentAnswerResult submit(
      Actor actor, AnswerCommand command, List<QueryAttachment> attachments) {
    if (actor == null
        || command == null
        || attachments == null
        || attachments.size() > 3
        || attachments.stream().anyMatch(item -> item == null)
        || attachments.stream().mapToLong(item -> item.content().length).sum()
            > 20L * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    var inputs = List.copyOf(attachments);
    if (closed.get() || (!inputs.isEmpty() && queries == null)) {
      throw unavailable();
    }
    var processing = new Processing(!inputs.isEmpty());
    var reservation = evidence.operationGate().reserve();
    if (!admission.tryAcquire()) {
      reservation.close();
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "问答任务已达并发上限。");
    }
    var result = new CompletableFuture<AttachmentAnswerResult>();
    try {
      executor.execute(
          () -> {
            try (var operation = reservation.begin()) {
              processing.thread.set(Thread.currentThread());
              try {
                result.complete(execute(actor, command, inputs, processing));
              } catch (RuntimeException | Error failure) {
                result.completeExceptionally(failure);
              } finally {
                processing.thread.set(null);
                admission.release();
              }
            }
          });
    } catch (RejectedExecutionException rejected) {
      reservation.close();
      admission.release();
      throw unavailable();
    } catch (RuntimeException | Error failedSubmission) {
      reservation.close();
      admission.release();
      throw failedSubmission;
    }

    try {
      var value = result.get(Math.max(0, processing.remaining()), TimeUnit.NANOSECONDS);
      processing.check();
      return value;
    } catch (TimeoutException expired) {
      processing.cancel();
      throw timeout();
    } catch (InterruptedException interrupted) {
      processing.cancel();
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof ApplicationException application) {
        throw application;
      }
      throw unavailable();
    }
  }

  private AttachmentAnswerResult execute(
      Actor actor,
      AnswerCommand command,
      List<QueryAttachment> attachments,
      Processing processing) {
    processing.check();
    var scope = evidence.snapshot(actor, command.selection(), target);
    Proposal proposed;
    QueryTrace queryTrace = null;
    try {
      var query = PreparedQuery.text(command.question());
      if (!attachments.isEmpty()) {
        if (scope.publications().isEmpty()) {
          queryTrace = failedQuery(command.question(), attachments, "empty_scope");
        } else {
          query =
              queries.prepare(command.question(), attachments, () -> current(scope, processing));
          queryTrace = QueryTrace.prepared(query, queries.rankingRevision());
        }
      }
      proposed = propose(scope, query, processing);
    } catch (TextParser.Failure invalid) {
      if (queryTrace == null && !attachments.isEmpty()) {
        queryTrace = failedQuery(command.question(), attachments, invalid.code());
      }
      proposed = refused(command.question(), invalid.code());
    } catch (Rejected rejected) {
      proposed = refused(command.question(), rejected.reason);
    } catch (TextModels.Failure | ProjectionException invalid) {
      proposed = refused(command.question(), "upstream_invalid");
    } catch (ApplicationException invalid) {
      proposed =
          refused(
              command.question(),
              !processing.active()
                  ? "processing_timeout"
                  : "image_vector_required".equals(invalid.code())
                      ? invalid.code()
                      : "scope_changed");
    } catch (RuntimeException invalid) {
      proposed = refused(command.question(), "upstream_invalid");
    }
    if (!attachments.isEmpty() && queryTrace == null) {
      queryTrace = failedQuery(command.question(), attachments, proposed.trace().reasonCode());
    }
    var trace = proposed.trace().withQueryTrace(queryTrace);
    // Persistence failure must escape: no authoritative receipt means no answer is released.
    var receipt =
        evidence.finish(scope, trace, () -> eligibility(processing), processing.imageVectorScope);
    if (!"answered".equals(receipt.outcome()) || proposed.source() == null) {
      return AttachmentAnswerResult.from(
          "image",
          new VisualAnswerResult(
              receipt.traceId(),
              "abstained",
              "image_vector_required".equals(receipt.reasonCode()) ? "请为当前范围的全部图片建立原图向量。" : REFUSAL,
              receipt.reasonCode(),
              List.of()),
          queryTrace);
    }
    return AttachmentAnswerResult.from(
        "image",
        new VisualAnswerResult(
            receipt.traceId(),
            "answered",
            proposed.answer(),
            null,
            List.of(
                citation(
                    receipt.traceId(),
                    proposed.source(),
                    proposed.trace().visualEvidence().getFirst()))),
        queryTrace);
  }

  private QueryTrace failedQuery(
      String question, List<QueryAttachment> attachments, String reason) {
    return QueryTrace.failed(
        sha(question),
        attachments,
        queries.preparationRevision(),
        queries.rankingRevision(),
        reason);
  }

  private Proposal propose(EvidenceScope scope, PreparedQuery query, Processing processing) {
    String question = query.originalQuestion();
    if (scope.publications().isEmpty()) {
      return refused(question, "empty_scope");
    }
    current(scope, processing);
    var publications = evidence.imagePublications(scope);
    if (publications.isEmpty()) {
      return refused(question, "no_image_evidence");
    }
    // Query modality narrows retrieval only; scope is never replaced by this subset.
    var generations = new LinkedHashMap<String, String>();
    publications.forEach(p -> generations.put(p.documentId(), p.projectionGenerationId()));
    var authorized =
        new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
    List<RetrievalProjection.Candidate> candidates;
    if (!query.queryImages().isEmpty() && queries != null && queries.imageTarget() != null) {
      processing.imageVectorScope = evidence.imageVectorScope(scope, queries.imageTarget());
      generations.clear();
      processing
          .imageVectorScope
          .publications()
          .forEach(
              value ->
                  generations.put(
                      value.basePublication().documentId(), value.vectorGenerationId()));
      var vectorAuthorized =
          new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
      candidates =
          queries.searchImages(
              query,
              vectorAuthorized,
              () -> current(scope, processing),
              ids ->
                  evidence.hydrateImageVectors(processing.imageVectorScope, ids).stream()
                      .map(PublishedImageEvidence::physicalSegmentId)
                      .toList());
    } else {
      candidates =
          query.attachments().isEmpty()
              ? search(scope, question, authorized, processing)
              : queries.search(
                  query,
                  authorized,
                  () -> current(scope, processing),
                  ids -> evidence.hydrateImages(scope, ids));
    }
    current(scope, processing);
    if (candidates == null || candidates.size() > 64) {
      throw rejected("upstream_invalid");
    }
    var unique = new HashSet<String>();
    for (var candidate : candidates) {
      if (candidate == null
          || !unique.add(candidate.segmentId())
          || !Double.isFinite(candidate.score())) {
        throw rejected("upstream_invalid");
      }
    }
    if (candidates.isEmpty()) {
      return refused(question, "no_image_evidence");
    }
    var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
    var sources = evidence.hydrateImages(scope, ids);
    current(scope, processing);
    var ranks =
        query.attachments().isEmpty()
            ? text.rerank(question, sources.stream().map(s -> s.image().recallText()).toList())
            : rank(scope, query, sources, processing);
    current(scope, processing);
    if (ranks == null || ranks.size() != sources.size()) {
      throw rejected("upstream_invalid");
    }
    var rankedIds = new HashSet<Integer>();
    for (var rank : ranks) {
      if (rank == null
          || rank.index() < 0
          || rank.index() >= sources.size()
          || !rankedIds.add(rank.index())
          || !Double.isFinite(rank.score())) {
        throw rejected("upstream_invalid");
      }
    }
    var best =
        ranks.stream()
            .min(
                Comparator.comparingDouble(TextModels.Ranked::score)
                    .reversed()
                    .thenComparingInt(TextModels.Ranked::index))
            .orElseThrow();
    var selected = sources.get(best.index());
    var original = evidence.image(scope, selected.physicalSegmentId());
    current(scope, processing);
    // Each original-image request checks the same full authority scope, including uncited
    // documents.
    var assessment =
        new VisualAssessmentService(guarded(scope, processing))
            .assess(question, original.original());
    current(scope, processing);
    if (assessment.refusalReason() != null) {
      return refused(question, assessment.refusalReason());
    }
    if (!assessment.sourceSha256().equals(selected.publication().sourceSha256())
        || !assessment.modelRevision().equals(visualRevision)) {
      throw rejected("configuration_changed");
    }
    String answer = String.join("\n", assessment.claims());
    var visual =
        new VisualTraceEvidence(
            1,
            selected.physicalSegmentId(),
            candidates.get(best.index()).score(),
            best.score(),
            assessment.claims().stream().map(VisualAnswerService::sha).toList(),
            assessment.modelRevision(),
            assessment.policyRevision());
    return new Proposal(
        answer,
        selected,
        new TraceDraft(
            sha(question),
            sha(answer),
            "answered",
            null,
            target.modelRevision(),
            PROMPT_REVISION,
            VisualAssessmentService.POLICY_REVISION,
            List.of(),
            List.of(visual)));
  }

  private VisionModels guarded(EvidenceScope scope, Processing processing) {
    return new VisionModels() {
      @Override
      public Description describe(VisualImage image) {
        throw new IllegalStateException("Description is not answer evidence");
      }

      @Override
      public Draft draft(String question, VisualImage image) {
        current(scope, processing);
        return vision.draft(question, image);
      }

      @Override
      public Verification verify(String question, VisualImage image, List<String> claims) {
        current(scope, processing);
        return vision.verify(question, image, claims);
      }

      @Override
      public String revision() {
        return vision.revision();
      }
    };
  }

  private List<RetrievalProjection.Candidate> search(
      EvidenceScope scope,
      String question,
      RetrievalProjection.AuthorizedScope authorized,
      Processing processing) {
    projection.prepareSearch();
    current(scope, processing);
    var vectors = text.embed(List.of(question));
    current(scope, processing);
    if (vectors == null
        || vectors.size() != 1
        || vectors.getFirst() == null
        || vectors.getFirst().size() != target.dimensions()) {
      throw rejected("upstream_invalid");
    }
    return projection.search(
        new RetrievalProjection.Query(question, vectors.getFirst(), authorized, 64));
  }

  private List<TextModels.Ranked> rank(
      EvidenceScope scope,
      PreparedQuery query,
      List<PublishedImageEvidence> sources,
      Processing processing) {
    var candidates = new ArrayList<QueryRankCandidate>();
    for (var source : sources) {
      current(scope, processing);
      VisualImage image = null;
      if (!query.queryImages().isEmpty()) {
        var original = evidence.image(scope, source.physicalSegmentId());
        if (!original.source().equals(source)) {
          throw rejected("upstream_invalid");
        }
        image = original.original();
      }
      candidates.add(new QueryRankCandidate(source.image().recallText(), image));
    }
    return queries.rank(query, candidates, () -> current(scope, processing));
  }

  private void current(EvidenceScope scope, Processing processing) {
    processing.check();
    if (!configurationCurrent(processing)) {
      throw rejected("configuration_changed");
    }
    if (processing.imageVectorScope == null) {
      evidence.hydrateImages(scope, List.of());
    } else {
      evidence.hydrateImageVectors(processing.imageVectorScope, List.of());
    }
    processing.check();
  }

  private boolean configurationCurrent() {
    return textRevision.equals(text.revision())
        && target.projectionIdentity().equals(projection.identity())
        && visualRevision.equals(vision.revision());
  }

  private boolean configurationCurrent(Processing processing) {
    return configurationCurrent()
        && (!processing.attached || queries.configurationCurrent())
        && (processing.imageVectorScope == null || queries.imageConfigurationCurrent());
  }

  private AnswerEligibility eligibility(Processing processing) {
    if (!processing.active()) {
      return AnswerEligibility.PROCESSING_TIMEOUT;
    }
    if (!configurationCurrent(processing)) {
      return AnswerEligibility.CONFIGURATION_CHANGED;
    }
    return processing.active() ? AnswerEligibility.ELIGIBLE : AnswerEligibility.PROCESSING_TIMEOUT;
  }

  private Proposal refused(String question, String reason) {
    return new Proposal(
        REFUSAL,
        null,
        new TraceDraft(
            sha(question),
            null,
            "abstained",
            reason,
            target.modelRevision(),
            PROMPT_REVISION,
            VisualAssessmentService.POLICY_REVISION,
            List.of()));
  }

  public VisualSourceResult source(Actor actor, String answerId, int ordinal) {
    if (closed.get()) {
      throw unavailable();
    }
    var value = evidence.visualSource(actor, answerId, ordinal);
    return new VisualSourceResult(answerId, citation(answerId, value.source(), value.trace()));
  }

  public VisualImage content(Actor actor, String answerId, int ordinal) {
    if (closed.get()) {
      throw unavailable();
    }
    return evidence.visualSource(actor, answerId, ordinal).original();
  }

  private static VisualCitationResult citation(
      String traceId, PublishedImageEvidence source, VisualTraceEvidence trace) {
    var publication = source.publication();
    String url = "/v1/visual-sources/" + traceId + "/" + trace.citationOrdinal();
    return new VisualCitationResult(
        trace.citationOrdinal(),
        "image_region",
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        source.filename(),
        source.mediaType(),
        source.image().width(),
        source.image().height(),
        List.of(0.0, 0.0, 1.0, 1.0),
        "normalized_xyxy",
        trace.visualModelRevision(),
        trace.visualPolicyRevision(),
        url,
        url + "/content");
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private record Proposal(String answer, PublishedImageEvidence source, TraceDraft trace) {
    @Override
    public String toString() {
      return "Proposal[redacted]";
    }
  }

  private static Rejected rejected(String reason) {
    return new Rejected(reason);
  }

  private static final class Rejected extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private Rejected(String reason) {
      super("Visual evidence rejected", null, false, false);
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
    private final boolean attached;
    private ImageVectorScope imageVectorScope;
    private final long started = System.nanoTime();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    private Processing(boolean attached) {
      this.attached = attached;
    }

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
        throw new ApplicationException(FailureKind.TIMEOUT, "answer_shutdown_timeout", "问答任务尚未退出。");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ApplicationException(
          FailureKind.TIMEOUT, "answer_shutdown_interrupted", "等待问答任务退出被中断。");
    }
  }
}
