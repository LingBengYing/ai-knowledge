package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.AudioSourceEvidence;
import com.evidence.rag.model.domain.AudioTraceEvidence;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.PublishedAudioEvidence;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.PublishedVideoOcrEvidence;
import com.evidence.rag.model.domain.PublishedVideoSubtitleEvidence;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.QueryTrace;
import com.evidence.rag.model.domain.SourceAudio;
import com.evidence.rag.model.domain.SourceEvidence;
import com.evidence.rag.model.domain.SourceVideo;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoOcrSourceEvidence;
import com.evidence.rag.model.domain.VideoSourceEvidence;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleSourceEvidence;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerPayload;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.model.dto.AttachmentAnswerResult;
import com.evidence.rag.model.dto.AudioAnswerResult;
import com.evidence.rag.model.dto.AudioCitationResult;
import com.evidence.rag.model.dto.AudioSourceResult;
import com.evidence.rag.model.dto.CitationResult;
import com.evidence.rag.model.dto.ImageSourceResult;
import com.evidence.rag.model.dto.ImageTextRegionResult;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.model.dto.QueryAttachmentCommand;
import com.evidence.rag.model.dto.SourceResult;
import com.evidence.rag.model.dto.TraceReceipt;
import com.evidence.rag.model.dto.VideoAnswerResult;
import com.evidence.rag.model.dto.VideoCitationResult;
import com.evidence.rag.model.dto.VideoFrameResult;
import com.evidence.rag.model.dto.VideoOcrResult;
import com.evidence.rag.model.dto.VideoSourceResult;
import com.evidence.rag.model.dto.VideoSubtitleResult;
import com.evidence.rag.model.dto.VideoTranscriptResult;
import com.evidence.rag.tool.answer.TextGrounding;
import com.evidence.rag.tool.parser.TextParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import java.util.function.Function;

/**
 * Authorized query orchestration. Only the authority's final trace receipt can release an answer.
 */
public final class AnswerService implements AutoCloseable {
  public static final String PROMPT_REVISION = "java-extractive-answer-v1";
  private static final int MAX_CANDIDATES = 64;
  private static final int MAX_QUOTES = 32;
  private static final long MAX_CONTEXT_BYTES = 8L * 1024 * 1024;
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
  private final VideoAnswerProposalService videoProposals;
  private final QueryAttachmentService queries;
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
    this(evidence, models, projection, target, deadline, maximumConcurrent, null);
  }

  public AnswerService(
      EvidenceService evidence,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int maximumConcurrent,
      VideoAnswerProposalService videoProposals) {
    this(evidence, models, projection, target, deadline, maximumConcurrent, videoProposals, null);
  }

  public AnswerService(
      EvidenceService evidence,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      Duration deadline,
      int maximumConcurrent,
      VideoAnswerProposalService videoProposals,
      QueryAttachmentService queries) {
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
    this.videoProposals = videoProposals;
    this.queries = queries;
    timeoutNanos = deadline.toNanos();
    admission = new Semaphore(maximumConcurrent);
    executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-answer-", 0).factory());
  }

  public AnswerResult answer(Actor actor, AnswerCommand command) {
    return submit(
        actor,
        command,
        SourceKind.TEXT,
        completed -> (AnswerResult) payload(SourceKind.TEXT, completed));
  }

  public AttachmentAnswerResult answerAttached(Actor actor, QueryAttachmentCommand command) {
    if (command == null || command.mode() == QueryAnswerMode.IMAGE) {
      throw ModelValues.invalid();
    }
    SourceKind kind = SourceKind.valueOf(command.mode().name());
    if (kind.video() || kind.videoText()) {
      requireVideo();
    }
    return submit(
        actor,
        command.answer(),
        kind,
        command.attachments(),
        completed ->
            AttachmentAnswerResult.from(
                command.mode().name().toLowerCase(Locale.ROOT),
                payload(kind, completed),
                completed.proposed().trace().queryTrace()));
  }

  private static AnswerPayload payload(SourceKind kind, Completed completed) {
    var receipt = completed.receipt();
    var proposed = completed.proposed();
    boolean answered = "answered".equals(receipt.outcome());
    String status = answered ? "answered" : "abstained";
    String text = answered ? proposed.answer() : REFUSAL;
    String reason = answered ? null : receipt.reasonCode();
    if (kind == SourceKind.TEXT) {
      var citations = new ArrayList<CitationResult>();
      if (answered) {
        for (var source : proposed.sources()) {
          citations.add(citation(receipt.traceId(), citations.size() + 1, source));
        }
      }
      return new AnswerResult(receipt.traceId(), status, text, reason, citations);
    }
    if (kind == SourceKind.AUDIO) {
      var citations = new ArrayList<AudioCitationResult>();
      if (answered) {
        for (var source : proposed.audioSources()) {
          citations.add(audioCitation(receipt.traceId(), citations.size() + 1, source));
        }
      }
      return new AudioAnswerResult(receipt.traceId(), status, text, reason, citations);
    }
    var citations = new ArrayList<VideoCitationResult>();
    if (answered) {
      for (var source : proposed.videoSources()) {
        citations.add(videoCitation(receipt.traceId(), citations.size() + 1, source));
      }
      for (var source : proposed.ocrSources()) {
        citations.add(videoOcrCitation(receipt.traceId(), citations.size() + 1, source));
      }
      for (var source : proposed.subtitleSources()) {
        citations.add(videoSubtitleCitation(receipt.traceId(), citations.size() + 1, source));
      }
    }
    return new VideoAnswerResult(receipt.traceId(), status, text, reason, citations);
  }

  public AudioAnswerResult answerAudio(Actor actor, AnswerCommand command) {
    return submit(
        actor,
        command,
        SourceKind.AUDIO,
        completed -> (AudioAnswerResult) payload(SourceKind.AUDIO, completed));
  }

  public VideoAnswerResult answerVideo(
      Actor actor, AnswerCommand command, VideoAssessment.Mode mode) {
    requireVideo();
    if (mode == null) {
      throw ModelValues.invalid();
    }
    SourceKind kind =
        switch (mode) {
          case VISUAL -> SourceKind.VIDEO_VISUAL;
          case TRANSCRIPT -> SourceKind.VIDEO_TRANSCRIPT;
          case JOINT -> SourceKind.VIDEO_JOINT;
        };
    return submit(actor, command, kind, completed -> (VideoAnswerResult) payload(kind, completed));
  }

  public VideoAnswerResult answerVideoOcr(Actor actor, AnswerCommand command) {
    requireVideo();
    return submit(
        actor,
        command,
        SourceKind.VIDEO_OCR,
        completed -> (VideoAnswerResult) payload(SourceKind.VIDEO_OCR, completed));
  }

  public VideoAnswerResult answerVideoSubtitle(Actor actor, AnswerCommand command) {
    requireVideo();
    return submit(
        actor,
        command,
        SourceKind.VIDEO_SUBTITLE,
        completed -> (VideoAnswerResult) payload(SourceKind.VIDEO_SUBTITLE, completed));
  }

  public VideoSourceResult videoSource(Actor actor, String answerId, int ordinal) {
    requireVideo();
    var subtitle = evidence.videoSubtitleSource(actor, answerId, ordinal);
    if (subtitle != null) {
      return new VideoSourceResult(answerId, videoSubtitleCitation(answerId, ordinal, subtitle));
    }
    var ocr = evidence.videoOcrSource(actor, answerId, ordinal);
    if (ocr != null) {
      return new VideoSourceResult(answerId, videoOcrCitation(answerId, ordinal, ocr));
    }
    var source = evidence.videoSource(actor, answerId, ordinal);
    return new VideoSourceResult(answerId, videoCitation(answerId, ordinal, source));
  }

  public VisualImage videoFrame(Actor actor, String answerId, int ordinal) {
    requireVideo();
    if (evidence.videoSubtitleSource(actor, answerId, ordinal) != null) {
      throw ModelValues.notFound();
    }
    var ocr = evidence.videoOcrSource(actor, answerId, ordinal);
    if (ocr != null) {
      return ocr.frame().image();
    }
    var frame = evidence.videoSource(actor, answerId, ordinal).source().proofInput().frame();
    if (frame == null) {
      throw ModelValues.notFound();
    }
    return frame.image();
  }

  public SourceVideo videoContent(Actor actor, String answerId, int ordinal) {
    requireVideo();
    var subtitle = evidence.videoSubtitleSource(actor, answerId, ordinal);
    if (subtitle != null) {
      return subtitle.video();
    }
    var ocr = evidence.videoOcrSource(actor, answerId, ordinal);
    if (ocr != null) {
      return ocr.video();
    }
    var video = evidence.videoSource(actor, answerId, ordinal).video();
    if (video == null) {
      throw unavailable();
    }
    return video;
  }

  private void requireVideo() {
    if (closed.get() || videoProposals == null) {
      throw unavailable();
    }
  }

  private <T> T submit(
      Actor actor, AnswerCommand command, SourceKind kind, Function<Completed, T> resultMapping) {
    return submit(actor, command, kind, List.of(), resultMapping);
  }

  private <T> T submit(
      Actor actor,
      AnswerCommand command,
      SourceKind kind,
      List<QueryAttachment> attachments,
      Function<Completed, T> resultMapping) {
    if (actor == null || command == null) {
      throw ModelValues.invalid();
    }
    if (closed.get() || (!attachments.isEmpty() && queries == null)) {
      throw unavailable();
    }
    var processing = new Processing(!attachments.isEmpty());
    if (!admission.tryAcquire()) {
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "问答任务已达并发上限。");
    }
    var result = new CompletableFuture<T>();
    try {
      // Cancelling the result must not prevent this runnable's finally from releasing admission.
      executor.execute(
          () -> {
            processing.thread.set(Thread.currentThread());
            try {
              result.complete(
                  resultMapping.apply(execute(actor, command, kind, attachments, processing)));
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
      T answer = result.get(Math.max(0, processing.remaining()), TimeUnit.NANOSECONDS);
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

  public AudioSourceResult audioSource(Actor actor, String answerId, int citationOrdinal) {
    if (closed.get()) {
      throw unavailable();
    }
    var source = evidence.audioSource(actor, answerId, citationOrdinal);
    return new AudioSourceResult(answerId, audioCitation(answerId, citationOrdinal, source));
  }

  public SourceAudio audioContent(Actor actor, String answerId, int citationOrdinal) {
    if (closed.get()) {
      throw unavailable();
    }
    var audio = evidence.audioSource(actor, answerId, citationOrdinal).audio();
    if (audio == null) {
      throw unavailable();
    }
    return audio;
  }

  public SourceResult source(Actor actor, String answerId, int citationOrdinal) {
    if (closed.get()) {
      throw unavailable();
    }
    SourceEvidence source = evidence.source(actor, answerId, citationOrdinal);
    var citation = citation(answerId, citationOrdinal, source);
    if (source.image() == null) {
      return new SourceResult(answerId, citation);
    }
    var image = source.image();
    return new SourceResult(
        answerId,
        citation,
        new ImageSourceResult(
            "image",
            image.mimeType(),
            image.width(),
            image.height(),
            List.of(0, 0, 1, 1),
            "normalized_xyxy",
            "machine_ocr",
            citation.sourceUrl() + "/content",
            image.regions().isEmpty() ? null : "ocr_word",
            image.regions().isEmpty()
                ? null
                : image.regions().stream()
                    .map(
                        region ->
                            new ImageTextRegionResult(
                                region.start(),
                                region.end(),
                                List.of(
                                    (double) region.left() / image.width(),
                                    (double) region.top() / image.height(),
                                    (double) region.right() / image.width(),
                                    (double) region.bottom() / image.height())))
                    .toList()));
  }

  private Completed execute(
      Actor actor,
      AnswerCommand command,
      SourceKind kind,
      List<QueryAttachment> attachments,
      Processing processing) {
    processing.check();
    // Invalid explicit selection is not an accepted query, and never falls back to ALL.
    EvidenceScope scope = evidence.snapshot(actor, command.selection(), target);
    Proposal proposed;
    QueryTrace queryTrace = null;
    try {
      var query = PreparedQuery.text(command.question());
      if (!attachments.isEmpty() && !scope.publications().isEmpty()) {
        query =
            queries.prepare(
                command.question(),
                attachments,
                () -> requireCurrent(scope, List.of(), kind, processing));
        queryTrace = QueryTrace.prepared(query, queries.rankingRevision());
      }
      proposed = propose(scope, query, kind, processing);
    } catch (TextParser.Failure failure) {
      proposed = abstention(command.question(), failure.code(), kind);
    } catch (RejectedEvidence failure) {
      proposed = abstention(command.question(), failure.reason, kind);
    } catch (TextModels.Failure | ProjectionException | CancellationException failure) {
      proposed =
          abstention(
              command.question(),
              processing.active() ? "upstream_unavailable" : "processing_timeout",
              kind);
    } catch (ApplicationException failure) {
      if ("scope_changed".equals(failure.code())) {
        proposed = abstention(command.question(), "scope_changed", kind);
      } else if ("configuration_changed".equals(failure.code())) {
        proposed = abstention(command.question(), "configuration_changed", kind);
      } else if (failure.kind() == FailureKind.TIMEOUT || !processing.active()) {
        proposed = abstention(command.question(), "processing_timeout", kind);
      } else if (failure.kind() == FailureKind.INVALID_INPUT
          || failure.kind() == FailureKind.NOT_FOUND) {
        proposed = abstention(command.question(), "upstream_invalid", kind);
      } else if (failure.kind() == FailureKind.CAPACITY_EXCEEDED) {
        proposed = abstention(command.question(), "evidence_capacity_exceeded", kind);
      } else {
        throw failure;
      }
    }
    if (!attachments.isEmpty()) {
      if (queryTrace == null) {
        queryTrace =
            QueryTrace.failed(
                sha(command.question()),
                attachments,
                queries.preparationRevision(),
                queries.rankingRevision(),
                proposed.trace().reasonCode());
      }
      proposed = proposed.withTrace(proposed.trace().withQueryTrace(queryTrace));
    }
    // Keep audit/storage failures outside the upstream-error catch: no durable trace, no answer.
    var receipt =
        evidence.finish(scope, proposed.trace(), () -> commitEligibility(processing, kind));
    if ("answered".equals(receipt.outcome()) && !"answered".equals(proposed.trace().outcome())) {
      throw unavailable();
    }
    return new Completed(receipt, proposed);
  }

  private Proposal propose(
      EvidenceScope scope, PreparedQuery query, SourceKind kind, Processing processing) {
    String question = query.originalQuestion();
    processing.check();
    if (scope.publications().isEmpty()) {
      return abstention(question, "empty_scope", kind);
    }
    requireCurrent(scope, List.of(), kind, processing);
    if (kind.video()) {
      var proposal =
          query.attachments().isEmpty()
              ? videoProposals.propose(
                  scope,
                  question,
                  kind.mode(),
                  () -> requireCurrent(scope, List.of(), kind, processing))
              : videoProposals.propose(
                  scope,
                  query,
                  kind.mode(),
                  queries,
                  () -> requireCurrent(scope, List.of(), kind, processing));
      // Proof adapters may translate callback exceptions; preserve the parent budget/config reason.
      requireCurrent(scope, List.of(), kind, processing);
      return new Proposal(
          proposal.answer(), List.of(), List.of(), proposal.sources(), proposal.trace());
    }
    List<List<Double>> embedded = List.of();
    if (query.attachments().isEmpty()) {
      projection.prepareSearch();
      requireCurrent(scope, List.of(), kind, processing);
      embedded = models.embed(List.of(question));
      processing.check();
      if (embedded == null
          || embedded.size() != 1
          || embedded.getFirst() == null
          || embedded.getFirst().size() != target.dimensions()) {
        throw rejected("upstream_invalid");
      }
    }
    var generations = new LinkedHashMap<String, String>();
    // Query modality narrows retrieval only; scope is never replaced by this subset.
    (kind == SourceKind.VIDEO_SUBTITLE
            ? evidence.videoSubtitlePublications(scope)
            : kind == SourceKind.VIDEO_OCR
                ? evidence.videoOcrPublications(scope)
                : kind == SourceKind.AUDIO
                    ? evidence.audioPublications(scope)
                    : evidence.textPublications(scope))
        .forEach(p -> generations.put(p.documentId(), p.projectionGenerationId()));
    requireCurrent(scope, List.of(), kind, processing);
    var authorized =
        new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
    var candidates =
        query.attachments().isEmpty()
            ? projection.search(
                new RetrievalProjection.Query(
                    question, embedded.getFirst(), authorized, MAX_CANDIDATES))
            : queries.search(
                query,
                authorized,
                () -> requireCurrent(scope, List.of(), kind, processing),
                ids -> requireCurrent(scope, ids, kind, processing));
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
      return abstention(question, "no_evidence", kind);
    }
    var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
    List<AnswerEvidence> sources = requireCurrent(scope, ids, kind, processing);
    if (sources.isEmpty()) {
      return abstention(question, "no_evidence", kind);
    }
    var rankings =
        query.attachments().isEmpty()
            ? models.rerank(
                question, sources.stream().map(source -> source.context().snippet()).toList())
            : queries.rank(
                query,
                sources.stream()
                    .map(source -> new QueryRankCandidate(source.context().snippet(), null))
                    .toList(),
                () -> requireCurrent(scope, ids, kind, processing));
    processing.check();
    var ordered = validatedRanking(rankings, sources.size());
    // Re-read every frozen publication, not just sources that reached the candidate list.
    sources = requireCurrent(scope, ids, kind, processing);
    var rankedEvidence = new ArrayList<TextModels.Evidence>();
    var rerankScores = new HashMap<String, Double>();
    for (var rank : ordered) {
      var source = sources.get(rank.index()).context();
      rankedEvidence.add(new TextModels.Evidence(source.physicalId(), source.snippet()));
      rerankScores.put(source.physicalId(), rank.score());
    }
    var extraction = models.extract(question, List.copyOf(rankedEvidence));
    sources = requireCurrent(scope, ids, kind, processing);
    if (extraction == null
        || extraction.quotes().size() > MAX_QUOTES
        || extraction.refused() != extraction.quotes().isEmpty()) {
      throw rejected("upstream_invalid");
    }
    if (extraction.refused()) {
      return abstention(question, "model_refused", kind);
    }
    var quotes = new ArrayList<GroundingQuote>();
    for (var quote : extraction.quotes()) {
      if (quote == null) {
        throw rejected("upstream_invalid");
      }
      quotes.add(new GroundingQuote(quote.evidenceId(), quote.quote()));
    }
    var grounded =
        grounding.verifyText(
            question, sources.stream().map(AnswerEvidence::context).toList(), quotes);
    processing.check();
    if (!grounded.supported()) {
      return abstention(
          question,
          GROUNDING_REASONS.contains(grounded.reason()) ? grounded.reason() : "incomplete_evidence",
          kind);
    }
    if (grounded.quotes().isEmpty() || grounded.quotes().size() > MAX_QUOTES) {
      throw rejected("invalid_quote");
    }
    Map<String, AnswerEvidence> byId = new HashMap<>();
    sources.forEach(source -> byId.put(source.context().physicalId(), source));
    var trace = new ArrayList<TraceEvidence>();
    var audioTrace = new ArrayList<AudioTraceEvidence>();
    var ocrTrace = new ArrayList<TraceEvidence>();
    var subtitleTrace = new ArrayList<TraceEvidence>();
    var cited = new ArrayList<SourceEvidence>();
    var audioCited = new ArrayList<AudioSourceEvidence>();
    var ocrCited = new ArrayList<VideoOcrSourceEvidence>();
    var subtitleCited = new ArrayList<VideoSubtitleSourceEvidence>();
    var text = new ArrayList<String>();
    var unique = new HashSet<String>();
    for (var quote : grounded.quotes()) {
      var source = byId.get(quote.physicalId());
      if (source == null
          || quote.start() < source.context().startCodePoint()
          || quote.end() > source.context().endCodePoint()
          || quote.end() <= quote.start()
          || !unique.add(quote.physicalId() + ":" + quote.start() + ":" + quote.end())) {
        throw rejected("invalid_quote");
      }
      if (kind == SourceKind.AUDIO) {
        var excerpt = new AudioSourceEvidence(source.audio(), quote.start(), quote.end());
        if (!excerpt.quote().equals(quote.quote())) {
          throw rejected("invalid_quote");
        }
        audioTrace.add(
            new AudioTraceEvidence(
                audioTrace.size() + 1,
                quote.physicalId(),
                quote.start(),
                quote.end(),
                retrievalScores.get(quote.physicalId()),
                rerankScores.get(quote.physicalId()),
                quote.factHashes()));
        audioCited.add(excerpt);
      } else if (kind == SourceKind.VIDEO_SUBTITLE) {
        var locator =
            new TraceEvidence(
                subtitleTrace.size() + 1,
                quote.physicalId(),
                quote.start(),
                quote.end(),
                retrievalScores.get(quote.physicalId()),
                rerankScores.get(quote.physicalId()),
                quote.factHashes());
        var excerpt = new VideoSubtitleSourceEvidence(source.subtitle(), locator, null);
        if (!excerpt.quote().equals(quote.quote())) {
          throw rejected("invalid_quote");
        }
        subtitleTrace.add(locator);
        subtitleCited.add(excerpt);
      } else if (kind == SourceKind.VIDEO_OCR) {
        var locator =
            new TraceEvidence(
                ocrTrace.size() + 1,
                quote.physicalId(),
                quote.start(),
                quote.end(),
                retrievalScores.get(quote.physicalId()),
                rerankScores.get(quote.physicalId()),
                quote.factHashes());
        var excerpt = evidence.videoOcrExcerpt(scope, locator);
        if (!excerpt.source().equals(source.ocr()) || !excerpt.quote().equals(quote.quote())) {
          throw rejected("invalid_quote");
        }
        ocrTrace.add(locator);
        ocrCited.add(excerpt);
      } else {
        var excerpt = new SourceEvidence(source.text(), quote.start(), quote.end());
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
      }
      text.add(quote.quote());
    }
    String answer = String.join("\n", text);
    return new Proposal(
        answer,
        List.copyOf(cited),
        List.copyOf(audioCited),
        List.of(),
        List.copyOf(ocrCited),
        List.copyOf(subtitleCited),
        new TraceDraft(
            sha(question),
            sha(answer),
            "answered",
            null,
            target.modelRevision(),
            PROMPT_REVISION,
            TextGrounding.VERSION,
            trace,
            List.of(),
            audioTrace,
            List.of(),
            null,
            ocrTrace,
            subtitleTrace));
  }

  private List<AnswerEvidence> requireCurrent(
      EvidenceScope scope, List<String> ids, SourceKind kind, Processing processing) {
    processing.check();
    if (!configurationCurrent(kind, processing)) {
      throw rejected("configuration_changed");
    }
    var sources =
        kind == SourceKind.VIDEO_SUBTITLE
            ? evidence.hydrateVideoSubtitle(scope, ids).stream()
                .map(source -> new AnswerEvidence(source.grounding(), null, null, null, source))
                .toList()
            : kind == SourceKind.VIDEO_OCR
                ? evidence.hydrateVideoOcr(scope, ids).stream()
                    .map(source -> new AnswerEvidence(source.grounding(), null, null, source, null))
                    .toList()
                : kind == SourceKind.AUDIO
                    ? evidence.hydrateAudio(scope, ids).stream()
                        .map(source -> new AnswerEvidence(source.transcript(), null, source))
                        .toList()
                    : evidence.hydrate(scope, ids).stream()
                        .map(
                            source ->
                                new AnswerEvidence(
                                    new GroundingText(
                                        source.physicalSegmentId(),
                                        source.publication().publicationId()
                                            + "/page/"
                                            + source.page().number(),
                                        source.page().text(),
                                        source.pageSha256(),
                                        source.segment().start(),
                                        source.segment().end()),
                                    source,
                                    null))
                        .toList();
    processing.check();
    if (!kind.videoText() && sources.size() != ids.size()) {
      throw rejected("upstream_invalid");
    }
    long bytes = 0;
    var contexts = new HashSet<String>();
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index).context();
      if (!kind.videoText() && !ids.get(index).equals(source.physicalId())) {
        throw rejected("upstream_invalid");
      }
      if (contexts.add(source.contextId())) {
        bytes += source.contextText().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONTEXT_BYTES) {
          throw rejected("evidence_capacity_exceeded");
        }
      }
    }
    return sources;
  }

  private AnswerEligibility commitEligibility(Processing processing, SourceKind kind) {
    if (!processing.active()) {
      return AnswerEligibility.PROCESSING_TIMEOUT;
    }
    if (!configurationCurrent(kind, processing)) {
      return AnswerEligibility.CONFIGURATION_CHANGED;
    }
    return processing.active() ? AnswerEligibility.ELIGIBLE : AnswerEligibility.PROCESSING_TIMEOUT;
  }

  private boolean configurationCurrent(SourceKind kind, Processing processing) {
    return target.modelRevision().equals(models.revision())
        && target.projectionIdentity().equals(projection.identity())
        && (!kind.video() || videoProposals.configurationCurrent())
        && (!processing.attached || queries.configurationCurrent());
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

  private Proposal abstention(String question, String reason, SourceKind kind) {
    return new Proposal(
        REFUSAL,
        List.of(),
        List.of(),
        List.of(),
        new TraceDraft(
            sha(question),
            null,
            "abstained",
            reason,
            kind.video() ? videoProposals.modelRevision() : target.modelRevision(),
            kind.video() ? VideoAnswerProposalService.PROMPT_REVISION : PROMPT_REVISION,
            kind.video() ? VideoAssessmentService.POLICY_REVISION : TextGrounding.VERSION,
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

  private static AudioCitationResult audioCitation(
      String traceId, int ordinal, AudioSourceEvidence source) {
    var value = source.evidence();
    var publication = value.publication();
    String quote = source.quote();
    String sourceUrl = "/v1/audio-sources/" + traceId + "/" + ordinal;
    return new AudioCitationResult(
        ordinal,
        "audio_span",
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        value.filename(),
        value.mediaType(),
        source.startMs(),
        source.endMs(),
        quote,
        sha(quote),
        "machine_asr",
        "server_chunk",
        sourceUrl,
        sourceUrl + "/content");
  }

  private static String sourceText(SourceEvidence source) {
    String page = source.evidence().page().text();
    return page.substring(
        page.offsetByCodePoints(0, source.start()), page.offsetByCodePoints(0, source.end()));
  }

  private static VideoCitationResult videoCitation(
      String traceId, int ordinal, VideoSourceEvidence evidence) {
    var material = evidence.source();
    var source = material.source();
    var publication = source.publication();
    var group = source.group();
    String sourceUrl = "/v1/video-sources/" + traceId + "/" + ordinal;
    VideoFrameResult frame = null;
    VideoTranscriptResult transcript = null;
    boolean visual = evidence.trace().kind() == VideoTraceEvidence.Kind.VISUAL;
    if (visual) {
      var original = material.proofInput().frame();
      if (original == null) {
        throw unavailable();
      }
      frame =
          new VideoFrameResult(
              original.presentationUs(),
              BigDecimal.valueOf(original.presentationUs(), 3),
              original.durationUs(),
              original.image().sha256(),
              original.width(),
              original.height(),
              original.image().mediaType(),
              "decoded_original",
              sourceUrl + "/frame");
    } else {
      var original = material.transcriptSpan();
      if (original == null) {
        throw unavailable();
      }
      String quote = evidence.quote();
      transcript =
          new VideoTranscriptResult(
              original.span().startMs(),
              original.span().endMs(),
              quote,
              sha(quote),
              "machine_asr",
              "server_chunk");
    }
    return new VideoCitationResult(
        ordinal,
        visual ? "video_frame" : "video_transcript",
        visual ? "machine_vlm" : "machine_asr",
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        source.filename(),
        source.mediaType(),
        group.id(),
        group.startUs(),
        group.endUs(),
        BigDecimal.valueOf(group.startUs(), 3),
        BigDecimal.valueOf(group.endUs(), 3),
        "group_interval",
        frame,
        transcript,
        sourceUrl,
        sourceUrl + "/content");
  }

  private static VideoCitationResult videoOcrCitation(
      String traceId, int ordinal, VideoOcrSourceEvidence evidence) {
    var source = evidence.source();
    var publication = source.publication();
    var original = evidence.frame();
    String sourceUrl = "/v1/video-sources/" + traceId + "/" + ordinal;
    long start = original.presentationUs();
    long end = start + original.durationUs();
    var frame =
        new VideoFrameResult(
            start,
            BigDecimal.valueOf(start, 3),
            original.durationUs(),
            original.image().sha256(),
            original.width(),
            original.height(),
            original.image().mediaType(),
            "decoded_original",
            sourceUrl + "/frame");
    var ocr =
        new VideoOcrResult(
            evidence.trace().start(),
            evidence.trace().end(),
            evidence.quote(),
            sha(evidence.quote()),
            source.ocrRevision(),
            evidence.regions());
    return new VideoCitationResult(
        ordinal,
        "video_frame_ocr",
        "machine_ocr",
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        source.filename(),
        source.mediaType(),
        null,
        start,
        end,
        BigDecimal.valueOf(start, 3),
        BigDecimal.valueOf(end, 3),
        "frame_interval",
        frame,
        null,
        sourceUrl,
        sourceUrl + "/content",
        ocr);
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static VideoCitationResult videoSubtitleCitation(
      String traceId, int ordinal, VideoSubtitleSourceEvidence evidence) {
    var source = evidence.source();
    var publication = source.publication();
    var cue = source.source();
    var track = source.track().track();
    String sourceUrl = "/v1/video-sources/" + traceId + "/" + ordinal;
    String quote = evidence.quote();
    var subtitle =
        new VideoSubtitleResult(
            cue.id(),
            source.track().id(),
            track.streamIndex(),
            track.codec(),
            track.language(),
            cue.cue().ordinal(),
            cue.cue().pts(),
            cue.cue().duration(),
            track.timeBaseNumerator(),
            track.timeBaseDenominator(),
            evidence.trace().start(),
            evidence.trace().end(),
            quote,
            sha(quote),
            cue.cue().payloadSha256(),
            source.subtitleManifestSha256(),
            source.nativeManifestSha256(),
            source.trackTextSha256(),
            source.decoderRevision(),
            VideoSubtitleCompilation.TEXT_FORMAT);
    return new VideoCitationResult(
        ordinal,
        "video_subtitle",
        "embedded_subtitle",
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        source.filename(),
        source.mediaType(),
        null,
        cue.startUs(),
        cue.endUs(),
        BigDecimal.valueOf(cue.startUs(), 3),
        BigDecimal.valueOf(cue.endUs(), 3),
        "subtitle_cue",
        null,
        null,
        sourceUrl,
        sourceUrl + "/content",
        null,
        subtitle);
  }

  private enum SourceKind {
    TEXT,
    AUDIO,
    VIDEO_OCR,
    VIDEO_SUBTITLE,
    VIDEO_VISUAL,
    VIDEO_TRANSCRIPT,
    VIDEO_JOINT;

    boolean video() {
      return this == VIDEO_VISUAL || this == VIDEO_TRANSCRIPT || this == VIDEO_JOINT;
    }

    boolean videoText() {
      return this == VIDEO_OCR || this == VIDEO_SUBTITLE;
    }

    VideoAssessment.Mode mode() {
      return switch (this) {
        case VIDEO_VISUAL -> VideoAssessment.Mode.VISUAL;
        case VIDEO_TRANSCRIPT -> VideoAssessment.Mode.TRANSCRIPT;
        case VIDEO_JOINT -> VideoAssessment.Mode.JOINT;
        default -> throw ModelValues.invalid();
      };
    }
  }

  private record AnswerEvidence(
      GroundingText context,
      PublishedEvidence text,
      PublishedAudioEvidence audio,
      PublishedVideoOcrEvidence ocr,
      PublishedVideoSubtitleEvidence subtitle) {
    private AnswerEvidence(
        GroundingText context, PublishedEvidence text, PublishedAudioEvidence audio) {
      this(context, text, audio, null, null);
    }

    @Override
    public String toString() {
      return "AnswerEvidence[redacted]";
    }
  }

  private record Completed(TraceReceipt receipt, Proposal proposed) {}

  private record Proposal(
      String answer,
      List<SourceEvidence> sources,
      List<AudioSourceEvidence> audioSources,
      List<VideoSourceEvidence> videoSources,
      List<VideoOcrSourceEvidence> ocrSources,
      List<VideoSubtitleSourceEvidence> subtitleSources,
      TraceDraft trace) {
    private Proposal(
        String answer,
        List<SourceEvidence> sources,
        List<AudioSourceEvidence> audioSources,
        List<VideoSourceEvidence> videoSources,
        TraceDraft trace) {
      this(answer, sources, audioSources, videoSources, List.of(), List.of(), trace);
    }

    private Proposal {
      sources = List.copyOf(sources);
      audioSources = List.copyOf(audioSources);
      videoSources = List.copyOf(videoSources);
      ocrSources = List.copyOf(ocrSources);
      subtitleSources = List.copyOf(subtitleSources);
    }

    private Proposal withTrace(TraceDraft updated) {
      return new Proposal(
          answer, sources, audioSources, videoSources, ocrSources, subtitleSources, updated);
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
    private final boolean attached;
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
