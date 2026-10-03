package com.evidence.rag.service;

import com.evidence.rag.client.model.SoundEmbeddingModels;
import com.evidence.rag.client.model.SoundModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundProof;
import com.evidence.rag.model.domain.SoundPublishedSpan;
import com.evidence.rag.model.domain.SoundScope;
import com.evidence.rag.model.domain.SoundTraceDraft;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.SoundAnswerResult;
import com.evidence.rag.model.dto.SoundAttachmentAnswerResult;
import com.evidence.rag.model.dto.SoundCitationResult;
import com.evidence.rag.model.dto.SoundQueryManifestResult;
import com.evidence.rag.model.dto.SoundSourceResult;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.answer.SoundProofBinding;
import com.evidence.rag.tool.parser.TextParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
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

/** Whole-question original-waveform proof, independent of ASR and recall descriptions. */
public final class SoundAnswerService implements AutoCloseable {
  public static final String POLICY_REVISION = SoundProofBinding.POLICY_REVISION;
  private static final String REFUSAL = "当前授权声音资料不足以可靠回答完整问题。";
  private final SqliteAuthorityStore store;
  private final SoundRepository repository;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final SoundCompilationService compiler;
  private final SoundModels models;
  private final SoundEmbeddingModels embedding;
  private final RetrievalProjection projection;
  private final IndexTarget target;
  private final String profile;
  private final String modelRevision;
  private final long budgetNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();

  public SoundAnswerService(
      SqliteAuthorityStore store,
      SoundRepository repository,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      SoundCompilationService compiler,
      SoundModels models,
      SoundEmbeddingModels embedding,
      RetrievalProjection projection,
      IndexTarget target,
      String profile,
      Duration budget,
      int concurrency) {
    this.store = Objects.requireNonNull(store);
    this.repository = Objects.requireNonNull(repository);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
    this.compiler = Objects.requireNonNull(compiler);
    this.models = Objects.requireNonNull(models);
    this.embedding = Objects.requireNonNull(embedding);
    this.projection = Objects.requireNonNull(projection);
    this.target = Objects.requireNonNull(target);
    this.profile = Objects.requireNonNull(profile);
    modelRevision = ModelValues.identifier(models.revision(), 200);
    if (budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(2)) > 0
        || concurrency < 1
        || concurrency > 2
        || !configurationCurrent()) {
      throw ModelValues.invalid();
    }
    budgetNanos = budget.toNanos();
    admission = new Semaphore(concurrency);
    executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sound-answer-", 0).factory());
  }

  public SoundAnswerResult answer(Actor actor, AnswerCommand command) {
    return submit(actor, command, List.of()).answer();
  }

  public SoundAttachmentAnswerResult answerAttached(
      Actor actor, AnswerCommand command, List<QueryAttachment> attachments) {
    if (attachments == null || attachments.isEmpty()) {
      throw ModelValues.invalid();
    }
    var value = submit(actor, command, attachments);
    var result = value.answer();
    return new SoundAttachmentAnswerResult(
        result.answerId(),
        result.status(),
        result.answer(),
        result.reasonCode(),
        result.citations(),
        result.policyRevision(),
        "SOUND",
        value.manifests());
  }

  private Outcome submit(Actor actor, AnswerCommand command, List<QueryAttachment> attachments) {
    if (actor == null
        || command == null
        || attachments == null
        || attachments.size() > 3
        || attachments.stream()
            .anyMatch(value -> value == null || value.kind() != QueryAttachment.Kind.AUDIO)
        || attachments.stream().mapToLong(value -> value.content().length).sum()
            > 20L * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    var inputs = List.copyOf(attachments);
    if (closed.get()) {
      throw unavailable();
    }
    var reservation = store.operationGate().reserve();
    if (!admission.tryAcquire()) {
      reservation.close();
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "声音问答已达并发上限。");
    }
    var processing = new Processing();
    var future = new CompletableFuture<Outcome>();
    try {
      executor.execute(
          () -> {
            try (var operation = reservation.begin()) {
              processing.worker.set(Thread.currentThread());
              try {
                future.complete(execute(actor, command, inputs, processing));
              } catch (RuntimeException | Error failure) {
                future.completeExceptionally(failure);
              } finally {
                processing.worker.set(null);
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
      var result = future.get(Math.max(0, processing.remaining()), TimeUnit.NANOSECONDS);
      processing.check();
      return result;
    } catch (InterruptedException interrupted) {
      processing.cancel();
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (TimeoutException expired) {
      processing.cancel();
      throw timeout();
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof ApplicationException safe) {
        throw safe;
      }
      throw unavailable();
    }
  }

  private Outcome execute(
      Actor actor,
      AnswerCommand command,
      List<QueryAttachment> attachments,
      Processing processing) {
    processing.check();
    requireConfiguration();
    // Complete scope qualification precedes every query decode or provider call.
    var scope =
        store.transaction(
            () -> {
              var value = repository.scope(actor, command.selection(), target, profile);
              for (var publication : value.publications()) {
                permissions.require(management.currentRole(actor, publication.documentId()), false);
              }
              return value;
            });
    List<SoundQueryManifestResult> manifests = List.of();
    List<SoundProof> proofs = List.of();
    String reason = null;
    try {
      if (scope.publications().isEmpty()) {
        reason = "empty_scope";
      } else {
        var prepared = prepare(scope, attachments, processing);
        manifests = prepared.manifests();
        var candidates = search(scope, command.question(), prepared.waveforms(), processing);
        proofs = assess(scope, command.question(), candidates, processing);
        if (proofs.isEmpty()) {
          reason = "incomplete_evidence";
        }
      }
    } catch (Rejected rejected) {
      reason = rejected.reason;
    } catch (TextParser.Failure invalid) {
      reason = invalid.code();
    } catch (ApplicationException invalid) {
      // No stale success or trace is released if authority, configuration or the budget moved.
      current(scope, processing);
      reason = "upstream_invalid";
    } catch (RuntimeException invalid) {
      current(scope, processing);
      reason = "upstream_invalid";
    }
    current(scope, processing);
    if (reason != null) {
      proofs = List.of();
    }
    String answer = proofs.isEmpty() ? REFUSAL : String.join("\n", proofs.getFirst().facts());
    var trace =
        new SoundTraceDraft(
            SoundProofBinding.sha(command.question()),
            proofs.isEmpty() ? null : SoundProofBinding.sha(answer),
            proofs.isEmpty() ? "abstained" : "answered",
            proofs.isEmpty() ? Objects.requireNonNullElse(reason, "no_evidence") : null,
            proofs,
            modelRevision,
            POLICY_REVISION);
    var receipt =
        store.transaction(
            () -> {
              processing.check();
              requireConfiguration();
              if (!repository.current(scope, target, profile)) {
                throw changed();
              }
              var value = repository.finish(scope, trace);
              processing.check();
              requireConfiguration();
              return value;
            });
    var citations = new ArrayList<SoundCitationResult>();
    for (int ordinal = 0; ordinal < proofs.size(); ordinal++) {
      citations.add(citation(receipt.traceId(), ordinal + 1, proofs.get(ordinal)));
    }
    return new Outcome(
        new SoundAnswerResult(
            receipt.traceId(),
            receipt.status(),
            answer,
            receipt.reasonCode(),
            citations,
            POLICY_REVISION),
        manifests);
  }

  private Prepared prepare(
      SoundScope scope, List<QueryAttachment> attachments, Processing processing) {
    var waveforms = new ArrayList<AudioWaveform>();
    var manifests = new ArrayList<SoundQueryManifestResult>();
    for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
      current(scope, processing);
      var attachment = attachments.get(ordinal);
      var decoded =
          compiler.decodeQuery(
              attachment.filename(),
              attachment.mediaType(),
              attachment.content(),
              () -> {
                current(scope, processing);
                return true;
              });
      current(scope, processing);
      String sourceSha = ModelValues.sha256(attachment.content());
      var fingerprint =
          new StringBuilder("sound-query-pcm-v1\0")
              .append(sourceSha)
              .append('\0')
              .append(compiler.revision());
      for (var waveform : decoded) {
        fingerprint
            .append('\0')
            .append(waveform.startSample())
            .append('\0')
            .append(waveform.endSample())
            .append('\0')
            .append(waveform.pcmSha256());
      }
      manifests.add(
          SoundQueryManifestResult.from(
              new QueryAttachmentManifest(
                  ordinal,
                  sourceSha,
                  QueryAttachment.Kind.AUDIO,
                  compiler.revision(),
                  SoundProofBinding.sha(fingerprint.toString()),
                  0,
                  0,
                  List.of(),
                  false)));
      waveforms.addAll(decoded);
    }
    return new Prepared(waveforms, manifests);
  }

  private List<SoundPublishedSpan> search(
      SoundScope scope, String question, List<AudioWaveform> query, Processing processing) {
    var generations = new LinkedHashMap<String, String>();
    scope
        .publications()
        .forEach(
            publication -> generations.put(publication.documentId(), publication.generationId()));
    var authorized =
        new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
    current(scope, processing);
    projection.prepareSearch();
    current(scope, processing);
    var fused = new LinkedHashMap<String, Double>();
    var material = new HashMap<String, SoundPublishedSpan>();
    var textVector = embedding.embedText(question);
    current(scope, processing);
    searchRoute(scope, question, textVector, authorized, fused, material, processing);
    for (var waveform : query) {
      current(scope, processing);
      var vector = embedding.embedAudio(waveform.wav());
      current(scope, processing);
      searchRoute(scope, question, vector, authorized, fused, material, processing);
    }
    return fused.entrySet().stream()
        .sorted(
            Comparator.<java.util.Map.Entry<String, Double>>comparingDouble(
                    java.util.Map.Entry::getValue)
                .reversed()
                .thenComparing(java.util.Map.Entry::getKey))
        .limit(64)
        .map(entry -> material.get(entry.getKey()))
        .toList();
  }

  private void searchRoute(
      SoundScope scope,
      String question,
      List<Double> vector,
      RetrievalProjection.AuthorizedScope authorized,
      LinkedHashMap<String, Double> fused,
      HashMap<String, SoundPublishedSpan> material,
      Processing processing) {
    if (vector == null || vector.size() != target.dimensions()) {
      throw new Rejected("upstream_invalid");
    }
    current(scope, processing);
    var candidates =
        projection.search(
            new RetrievalProjection.Query(
                question, vector, authorized, 64, RetrievalProjection.SearchMode.DENSE_ONLY));
    current(scope, processing);
    if (candidates == null
        || candidates.size() > 64
        || candidates.stream().anyMatch(Objects::isNull)
        || new HashSet<>(candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList())
                .size()
            != candidates.size()) {
      throw new Rejected("upstream_invalid");
    }
    // Authority maps the complete route, including its last candidate, before RRF can truncate.
    var sources =
        store.transaction(
            () ->
                repository.hydrate(
                    scope,
                    candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList()));
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index);
      material.put(source.span().physicalSegmentId(), source);
      fused.merge(source.span().physicalSegmentId(), 1.0 / (61 + index), Double::sum);
    }
  }

  private List<SoundProof> assess(
      SoundScope scope,
      String question,
      List<SoundPublishedSpan> candidates,
      Processing processing) {
    if (candidates.isEmpty()) {
      throw new Rejected("no_evidence");
    }
    // At most one original and its complete PCM windows live in memory during each document pass.
    var groups = new LinkedHashMap<String, List<SoundPublishedSpan>>();
    for (var candidate : candidates) {
      groups
          .computeIfAbsent(candidate.publication().documentId(), ignored -> new ArrayList<>())
          .add(candidate);
    }
    var supported = new HashMap<String, SoundProof>();
    for (var group : groups.values()) {
      var publication = group.getFirst().publication();
      current(scope, processing);
      var original =
          store.transaction(
              () ->
                  repository
                      .findOriginal(scope.actor(), publication.documentId())
                      .orElseThrow(ModelValues::notFound));
      var decoded =
          compiler.decode(
              original,
              () -> {
                current(scope, processing);
                return true;
              });
      if (decoded.size() != publication.spans().size()) {
        throw new Rejected("source_changed");
      }
      for (int index = 0; index < decoded.size(); index++) {
        var saved = publication.spans().get(index);
        var actual = decoded.get(index);
        if (!saved.id().equals(actual.id())
            || saved.ordinal() != actual.ordinal()
            || saved.startSample() != actual.waveform().startSample()
            || saved.endSample() != actual.waveform().endSample()
            || !saved.pcmSha256().equals(actual.waveform().pcmSha256())) {
          throw new Rejected("source_changed");
        }
      }
      for (var candidate : group) {
        var waveform = decoded.get(candidate.span().ordinal()).waveform();
        current(scope, processing);
        var draft = models.draft(question, waveform);
        current(scope, processing);
        if (draft == null) {
          throw new Rejected("upstream_invalid");
        }
        if (!draft.complete()) {
          continue;
        }
        if (!SoundProofBinding.safeFacts(draft.claims())) {
          throw new Rejected("unsafe_evidence");
        }
        var verification = models.verify(question, waveform, draft.claims());
        current(scope, processing);
        if (verification == null || verification.supported().size() != draft.claims().size()) {
          throw new Rejected("upstream_invalid");
        }
        if (!verification.complete()
            || verification.supported().stream().anyMatch(value -> !Boolean.TRUE.equals(value))) {
          continue;
        }
        supported.put(
            candidate.span().physicalSegmentId(),
            SoundProofBinding.create(
                SoundProofBinding.sha(question),
                candidate,
                draft.claims(),
                modelRevision,
                POLICY_REVISION));
      }
    }
    var proofs =
        candidates.stream()
            .map(source -> supported.get(source.span().physicalSegmentId()))
            .filter(Objects::nonNull)
            .toList();
    if (proofs.isEmpty()) {
      return List.of();
    }
    var firstFacts = new HashSet<>(proofs.getFirst().facts());
    if (proofs.stream().anyMatch(proof -> !firstFacts.equals(new HashSet<>(proof.facts())))) {
      throw new Rejected("conflicting_evidence");
    }
    // All candidates were independently checked; the strongest complete window supplies citation.
    return List.of(proofs.getFirst());
  }

  public SoundSourceResult source(Actor actor, String traceId, int ordinal) {
    requireConfiguration();
    var value =
        store.transaction(() -> repository.source(actor, traceId, ordinal, target, profile));
    requireConfiguration();
    return new SoundSourceResult(traceId, citation(traceId, ordinal, value.proof()));
  }

  public DocumentOriginal content(Actor actor, String traceId, int ordinal) {
    requireConfiguration();
    var value =
        store.transaction(() -> repository.source(actor, traceId, ordinal, target, profile));
    requireConfiguration();
    return value.original();
  }

  private static SoundCitationResult citation(String traceId, int ordinal, SoundProof proof) {
    var publication = proof.source().publication();
    var span = proof.source().span();
    String url = "/v1/sound-sources/" + traceId + "/" + ordinal;
    return new SoundCitationResult(
        ordinal,
        "sound_span",
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.id(),
        publication.profileFingerprint(),
        publication.decoderRevision(),
        span.pcmSha256(),
        publication.filename(),
        publication.mediaType(),
        span.startSample(),
        span.endSample(),
        16000,
        span.startSample() / 16,
        (span.endSample() + 15) / 16,
        proof.facts(),
        proof.factsSha256(),
        publication.soundModelRevision(),
        POLICY_REVISION,
        "server_window",
        url,
        url + "/content");
  }

  private boolean configurationCurrent() {
    return !closed.get()
        && compiler.configurationCurrent()
        && modelRevision.equals(models.revision())
        && target.embeddingIdentity().equals(embedding.revision())
        && target.modelRevision().equals(embedding.revision())
        && target.dimensions() == embedding.dimensions()
        && target.projectionIdentity().equals(projection.identity())
        && profile.equals(
            SoundProfile.fingerprint(
                target, modelRevision, compiler.decoderRevision(), compiler.chunkSeconds()));
  }

  private void requireConfiguration() {
    if (!configurationCurrent()) {
      throw new ApplicationException(FailureKind.CONFLICT, "configuration_changed", "声音服务配置已变化。");
    }
  }

  private void current(SoundScope scope, Processing processing) {
    processing.check();
    requireConfiguration();
    if (!store.transaction(() -> repository.current(scope, target, profile))) {
      throw changed();
    }
    processing.check();
  }

  private record Prepared(List<AudioWaveform> waveforms, List<SoundQueryManifestResult> manifests) {
    private Prepared {
      waveforms = List.copyOf(waveforms);
      manifests = List.copyOf(manifests);
    }

    @Override
    public String toString() {
      return "Prepared[redacted]";
    }
  }

  private record Outcome(SoundAnswerResult answer, List<SoundQueryManifestResult> manifests) {
    @Override
    public String toString() {
      return "Outcome[redacted]";
    }
  }

  private static final class Rejected extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private Rejected(String reason) {
      super("Sound proof unavailable", null, false, false);
      this.reason = reason;
    }
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "声音范围、权限或发布版本已变化。");
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "sound_answers_unavailable", "声音问答暂不可用。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "answer_timeout", "声音问答处理超过时限或已中断。");
  }

  private final class Processing {
    private final long started = System.nanoTime();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    long remaining() {
      return budgetNanos - (System.nanoTime() - started);
    }

    void check() {
      if (cancelled.get()
          || closed.get()
          || Thread.currentThread().isInterrupted()
          || remaining() <= 0) {
        throw timeout();
      }
    }

    void cancel() {
      cancelled.set(true);
      var thread = worker.get();
      if (thread != null) {
        thread.interrupt();
      }
    }
  }

  @Override
  public void close() {
    closed.set(true);
    executor.shutdownNow();
  }
}
