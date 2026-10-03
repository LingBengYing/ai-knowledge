package com.evidence.rag.service;

import com.evidence.rag.client.model.VideoAvEmbeddingModels;
import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEvidence;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvProof;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvQueryManifest;
import com.evidence.rag.model.domain.VideoAvQueryTrace;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvScope;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvTraceDraft;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import com.evidence.rag.model.dto.VideoAvAnswerResult;
import com.evidence.rag.model.dto.VideoAvAudioResult;
import com.evidence.rag.model.dto.VideoAvCitationResult;
import com.evidence.rag.model.dto.VideoAvEpochResult;
import com.evidence.rag.model.dto.VideoAvFactResult;
import com.evidence.rag.model.dto.VideoAvQueryAnswerResult;
import com.evidence.rag.model.dto.VideoAvQueryAttachmentResult;
import com.evidence.rag.model.dto.VideoAvQueryCommand;
import com.evidence.rag.model.dto.VideoAvSourceResult;
import com.evidence.rag.model.dto.VideoAvVideoResult;
import com.evidence.rag.model.dto.VideoAvWindowResult;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.answer.VideoAvProofBinding;
import com.evidence.rag.tool.parser.TextParser;
import java.math.BigInteger;
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

/** Complete original video/audio window proof with independent modality verification. */
public final class VideoAvAnswerService implements AutoCloseable {
  public static final String POLICY_REVISION = VideoAvProofBinding.POLICY_REVISION;
  private static final String REFUSAL = "当前授权视频音画资料不足以可靠回答完整问题。";
  private final SqliteAuthorityStore store;
  private final VideoAvRepository repository;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final VideoAvCompilationService compiler;
  private final VideoAvModels models;
  private final VideoAvEmbeddingModels embedding;
  private final RetrievalProjection visualProjection;
  private final RetrievalProjection audioProjection;
  private final VideoAvTargets targets;
  private final String profile;
  private final String modelRevision;
  private final long budgetNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();

  public VideoAvAnswerService(
      SqliteAuthorityStore store,
      VideoAvRepository repository,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      VideoAvCompilationService compiler,
      VideoAvModels models,
      VideoAvEmbeddingModels embedding,
      RetrievalProjection visualProjection,
      RetrievalProjection audioProjection,
      VideoAvTargets targets,
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
    this.visualProjection = Objects.requireNonNull(visualProjection);
    this.audioProjection = Objects.requireNonNull(audioProjection);
    this.targets = Objects.requireNonNull(targets);
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
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("video-av-answer-", 0).factory());
  }

  public VideoAvAnswerResult answer(Actor actor, VideoAvAnswerCommand command) {
    return submit(actor, command, List.of()).result();
  }

  public VideoAvQueryAnswerResult answerAttached(
      Actor actor, VideoAvAnswerCommand command, List<QueryAttachment> attachments) {
    var request = new VideoAvQueryCommand(command, attachments);
    var result = submit(actor, request.answer(), request.attachments());
    return new VideoAvQueryAnswerResult(
        command.mode().name(),
        result.result(),
        result.manifests().stream().map(VideoAvQueryAttachmentResult::from).toList());
  }

  private Outcome submit(
      Actor actor, VideoAvAnswerCommand command, List<QueryAttachment> attachments) {
    if (actor == null || command == null) {
      throw ModelValues.invalid();
    }

    if (closed.get()) {
      throw unavailable();
    }
    var reservation = store.operationGate().reserve();
    if (!admission.tryAcquire()) {
      reservation.close();
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "视频音画问答已达并发上限。");
    }
    var processing = new Processing();
    var future = new CompletableFuture<Outcome>();
    try {
      executor.execute(
          () -> {
            try (var operation = reservation.begin()) {
              processing.worker.set(Thread.currentThread());
              try {
                future.complete(execute(actor, command, attachments, processing));
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
      VideoAvAnswerCommand command,
      List<QueryAttachment> attachments,
      Processing processing) {

    processing.check();
    requireConfiguration();
    // Complete scope qualification precedes every query decode or provider call.
    var scope =
        store.transaction(
            () -> {
              var value = repository.scope(actor, command.answer().selection(), targets, profile);
              for (var publication : value.publications()) {
                permissions.require(management.currentRole(actor, publication.documentId()), false);
              }
              return value;
            });
    List<VideoAvProof> proofs = List.of();
    var manifests = new ArrayList<VideoAvQueryManifest>();
    for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
      manifests.add(
          VideoAvQueryManifest.notPrepared(
              ordinal, command.mode(), compiler.revision(), attachments.get(ordinal).sha256()));
    }
    String reason = null;
    try {
      if (scope.publications().isEmpty()) {
        reason = "empty_scope";
      } else {
        var prepared = prepare(scope, command.mode(), attachments, processing);
        manifests = new ArrayList<>(prepared.manifests());
        var candidates =
            search(
                scope,
                command.answer().question(),
                command.mode(),
                prepared.compilations(),
                processing);
        proofs = assess(scope, command.answer().question(), command.mode(), candidates, processing);
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
    String answer =
        proofs.isEmpty()
            ? REFUSAL
            : String.join("\n", proofs.getFirst().facts().stream().map(VideoAvFact::text).toList());
    var trace =
        new VideoAvTraceDraft(
            VideoAvProofIdentity.sha(command.answer().question()),
            proofs.isEmpty() ? null : VideoAvProofIdentity.sha(answer),
            proofs.isEmpty() ? "abstained" : "answered",
            proofs.isEmpty() ? Objects.requireNonNullElse(reason, "no_evidence") : null,
            command.mode(),
            proofs,
            modelRevision,
            POLICY_REVISION,
            attachments.isEmpty()
                ? null
                : new VideoAvQueryTrace(
                    command.mode(),
                    VideoAvProofIdentity.sha(command.answer().question()),
                    profile,
                    targets.visual().embeddingIdentity(),
                    manifests));
    var receipt =
        store.transaction(
            () -> {
              processing.check();
              requireConfiguration();
              if (!repository.current(scope, targets, profile)) {
                throw changed();
              }
              var value = repository.finish(scope, trace);
              processing.check();
              requireConfiguration();
              return value;
            });
    var citations = new ArrayList<VideoAvCitationResult>();
    for (int ordinal = 0; ordinal < proofs.size(); ordinal++) {
      citations.add(citation(receipt.traceId(), ordinal + 1, proofs.get(ordinal)));
    }
    return new Outcome(
        new VideoAvAnswerResult(
            receipt.traceId(),
            receipt.status(),
            command.mode().name(),
            answer,
            receipt.reasonCode(),
            citations,
            POLICY_REVISION),
        manifests);
  }

  private Prepared prepare(
      VideoAvScope scope,
      VideoAvMode mode,
      List<QueryAttachment> attachments,
      Processing processing) {
    var compilations = new ArrayList<VideoAvCompilation>();
    long windows = 0, clips = 0, pcm = 0;
    var durationMs = BigInteger.ZERO;
    for (var attachment : attachments) {
      current(scope, processing);
      var compilation =
          compiler.compileQuery(
              attachment,
              () -> {
                current(scope, processing);
                return true;
              });
      current(scope, processing);
      windows += compilation.windows().size();
      var numerator =
          BigInteger.valueOf(compilation.durationTick()).multiply(BigInteger.valueOf(1000));
      var divisor = BigInteger.valueOf(compilation.epoch().ticksPerSecond());
      durationMs = durationMs.add(numerator.add(divisor).subtract(BigInteger.ONE).divide(divisor));
      boolean visual = false, audio = false;
      for (var window : compilation.windows()) {
        if (window.video() != null) {
          visual = true;
          clips += window.video().content().length;
        }
        if (window.audio() != null) {
          audio = true;
          pcm += 2 * (window.audio().endSample() - window.audio().startSample());
        }
      }
      if (!visual || mode != VideoAvMode.VISUAL && (!compilation.hasAudio() || !audio)) {
        throw new Rejected("query_modality_missing");
      }
      if (windows > VideoAvCompilation.MAX_WINDOWS
          || clips > VideoAvCompilation.MAX_CLIP_BYTES
          || pcm > 19200000
          || durationMs.compareTo(BigInteger.valueOf(600000)) > 0) {
        throw new Rejected("query_preparation_limit");
      }
      compilations.add(compilation);
    }
    var manifests = new ArrayList<VideoAvQueryManifest>();
    for (int ordinal = 0; ordinal < compilations.size(); ordinal++) {
      manifests.add(
          VideoAvQueryManifest.prepared(
              ordinal, mode, compiler.revision(), compilations.get(ordinal)));
    }
    return new Prepared(compilations, manifests);
  }

  private List<VideoAvEvidence> search(
      VideoAvScope scope,
      String question,
      VideoAvMode mode,
      List<VideoAvCompilation> compilations,
      Processing processing) {
    boolean visual =
        mode != VideoAvMode.AUDIO
            && scope.publications().stream().anyMatch(p -> p.visualReceipt().count() > 0);
    boolean audio =
        mode != VideoAvMode.VISUAL
            && scope.publications().stream().anyMatch(p -> p.audioReceipt().count() > 0);
    if (!visual && !audio) {
      return List.of();
    }
    var generations = new LinkedHashMap<String, String>();
    scope.publications().forEach(p -> generations.put(p.documentId(), p.id()));
    var authorized =
        new RetrievalProjection.AuthorizedScope(scope.actor().workspaceId(), generations);
    var fused = new LinkedHashMap<String, Double>();
    var material = new HashMap<String, VideoAvEvidence>();
    current(scope, processing);
    var vector = embedding.embedText(question);
    current(scope, processing);
    validateVector(vector);
    if (visual) {
      searchRoute(
          scope, VideoAvRoute.VISUAL, question, vector, authorized, fused, material, processing);
    }
    if (audio) {
      searchRoute(
          scope, VideoAvRoute.AUDIO, question, vector, authorized, fused, material, processing);
    }
    for (var compilation : compilations) {
      for (var window : compilation.windows()) {
        if (visual && window.video() != null) {
          current(scope, processing);
          var mediaVector = embedding.embedVideo(window.video());
          current(scope, processing);
          validateVector(mediaVector);
          searchRoute(
              scope,
              VideoAvRoute.VISUAL,
              question,
              mediaVector,
              authorized,
              fused,
              material,
              processing);
        }
        if (audio && window.audio() != null) {
          current(scope, processing);
          var mediaVector = embedding.embedAudio(window.audio());
          current(scope, processing);
          validateVector(mediaVector);
          searchRoute(
              scope,
              VideoAvRoute.AUDIO,
              question,
              mediaVector,
              authorized,
              fused,
              material,
              processing);
        }
      }
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

  private void validateVector(List<Double> vector) {
    if (vector == null
        || vector.size() != targets.visual().dimensions()
        || vector.stream()
            .anyMatch(
                value ->
                    value == null || !Double.isFinite(value) || !Float.isFinite(value.floatValue()))
        || vector.stream().allMatch(value -> value.floatValue() == 0.0f)) {
      throw new Rejected("upstream_invalid");
    }
  }

  private record Prepared(
      List<VideoAvCompilation> compilations, List<VideoAvQueryManifest> manifests) {
    private Prepared {
      compilations = List.copyOf(compilations);
      manifests = List.copyOf(manifests);
    }

    @Override
    public String toString() {
      return "Prepared[redacted]";
    }
  }

  private record Outcome(VideoAvAnswerResult result, List<VideoAvQueryManifest> manifests) {
    private Outcome {
      manifests = List.copyOf(manifests);
    }

    @Override
    public String toString() {
      return "Outcome[redacted]";
    }
  }

  private void searchRoute(
      VideoAvScope scope,
      VideoAvRoute route,
      String question,
      List<Double> vector,
      RetrievalProjection.AuthorizedScope authorized,
      LinkedHashMap<String, Double> fused,
      HashMap<String, VideoAvEvidence> material,
      Processing processing) {
    var projection = route == VideoAvRoute.VISUAL ? visualProjection : audioProjection;
    current(scope, processing);
    projection.prepareSearch();
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
    var sources =
        store.transaction(
            () ->
                repository.hydrate(
                    scope,
                    route,
                    candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList()));
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index);
      String key = key(source);
      material.put(key, source);
      fused.merge(key, 1.0 / (61 + index), Double::sum);
    }
  }

  private List<VideoAvProof> assess(
      VideoAvScope scope,
      String question,
      VideoAvMode mode,
      List<VideoAvEvidence> candidates,
      Processing processing) {
    if (candidates.isEmpty()) {
      throw new Rejected("no_evidence");
    }
    var groups = new LinkedHashMap<String, List<VideoAvEvidence>>();
    for (var candidate : candidates) {
      groups
          .computeIfAbsent(candidate.publication().documentId(), ignored -> new ArrayList<>())
          .add(candidate);
    }
    var supported = new HashMap<String, VideoAvProof>();
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
          compiler.compile(
              original,
              () -> {
                current(scope, processing);
                return true;
              });
      current(scope, processing);
      if (!VideoAvProfile.compiledMatches(publication, decoded)) {
        throw new Rejected("source_changed");
      }
      for (var candidate : group) {
        var actual = decoded.windows().get(candidate.window().ordinal());
        if ((mode != VideoAvMode.AUDIO && actual.video() == null)
            || (mode != VideoAvMode.VISUAL && actual.audio() == null)) {
          continue;
        }
        var input =
            new VideoAvWindow(
                actual.id(),
                actual.ordinal(),
                actual.startTick(),
                actual.endTick(),
                mode == VideoAvMode.AUDIO ? null : actual.video(),
                mode == VideoAvMode.VISUAL ? null : actual.audio());
        current(scope, processing);
        var draft = models.draft(question, input, decoded.epoch(), mode);
        current(scope, processing);
        if (draft == null || draft.claims() == null) {
          throw new Rejected("upstream_invalid");
        }
        if (!draft.complete()) {
          continue;
        }
        var claims = new ArrayList<VideoAvFact>();
        for (int ordinal = 0; ordinal < draft.claims().size(); ordinal++) {
          var claim = draft.claims().get(ordinal);
          if (claim == null
              || claim.requirement() == null
              || (mode == VideoAvMode.VISUAL && claim.requirement() != VideoAvRequirement.VISUAL)
              || (mode == VideoAvMode.AUDIO && claim.requirement() != VideoAvRequirement.AUDIO)) {
            throw new Rejected("upstream_invalid");
          }
          claims.add(
              new VideoAvFact(
                  VideoAvProofIdentity.stableFactId(
                      VideoAvProofIdentity.sha(question),
                      ordinal,
                      claim.text(),
                      claim.requirement()),
                  claim.text(),
                  claim.requirement(),
                  false,
                  false));
        }
        if (!VideoAvProofBinding.safeFacts(claims)) {
          throw new Rejected("unsafe_evidence");
        }
        current(scope, processing);
        var verification =
            models.verify(question, input, decoded.epoch(), mode, List.copyOf(claims));
        current(scope, processing);
        if (verification == null
            || verification.support() == null
            || verification.support().size() != claims.size()) {
          throw new Rejected("upstream_invalid");
        }
        var byId = new HashMap<String, VideoAvModels.Support>();
        for (var item : verification.support()) {
          if (item == null || byId.put(item.id(), item) != null) {
            throw new Rejected("upstream_invalid");
          }
        }
        var verified = new ArrayList<VideoAvFact>();
        boolean allSupported = verification.complete();
        for (var claim : claims) {
          var item = byId.get(claim.id());
          if (item == null) {
            throw new Rejected("upstream_invalid");
          }
          allSupported &= item.supported();
          verified.add(
              new VideoAvFact(
                  claim.id(),
                  claim.text(),
                  claim.requirement(),
                  item.visualContribution(),
                  item.audioContribution()));
        }
        if (allSupported && VideoAvProofBinding.supported(mode, verified)) {
          supported.put(
              key(candidate),
              VideoAvProofBinding.create(
                  VideoAvProofIdentity.sha(question),
                  candidate,
                  mode,
                  verified,
                  modelRevision,
                  POLICY_REVISION));
        }
      }
    }
    var proofs =
        candidates.stream()
            .map(candidate -> supported.get(key(candidate)))
            .filter(Objects::nonNull)
            .toList();
    if (proofs.isEmpty()) {
      return List.of();
    }
    var first = semantics(proofs.getFirst());
    if (proofs.stream().anyMatch(proof -> !first.equals(semantics(proof)))) {
      throw new Rejected("conflicting_evidence");
    }
    return List.of(proofs.getFirst());
  }

  private static java.util.Set<FactMeaning> semantics(VideoAvProof proof) {
    var result = new HashSet<FactMeaning>();
    proof
        .facts()
        .forEach(
            f ->
                result.add(
                    new FactMeaning(
                        f.text(), f.requirement(), f.visualContribution(), f.audioContribution())));
    return result;
  }

  private record FactMeaning(
      String text, VideoAvRequirement requirement, boolean visual, boolean audio) {
    @Override
    public String toString() {
      return "FactMeaning[redacted]";
    }
  }

  private static String key(VideoAvEvidence evidence) {
    return evidence.publication().id() + ":" + evidence.window().id();
  }

  public VideoAvSourceResult source(Actor actor, String traceId, int ordinal) {
    requireConfiguration();
    var value =
        store.transaction(() -> repository.source(actor, traceId, ordinal, targets, profile));
    requireConfiguration();
    return new VideoAvSourceResult(traceId, citation(traceId, ordinal, value.proof()));
  }

  public DocumentOriginal content(Actor actor, String traceId, int ordinal) {
    requireConfiguration();
    var value =
        store.transaction(() -> repository.source(actor, traceId, ordinal, targets, profile));
    requireConfiguration();
    return value.original();
  }

  private static VideoAvCitationResult citation(String traceId, int ordinal, VideoAvProof proof) {
    var publication = proof.evidence().publication();
    var window = proof.evidence().window();
    var epoch = publication.epoch();
    var video = window.video();
    var audio = window.audio();
    String url = "/v1/video-av-sources/" + traceId + "/" + ordinal;
    long startMs = epoch.startMs(window.startTick());
    long endMs = epoch.endMs(window.endTick());
    return new VideoAvCitationResult(
        ordinal,
        "video_av_window",
        proof.mode().name(),
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.id(),
        publication.profileFingerprint(),
        publication.decoderRevision(),
        publication.filename(),
        publication.mediaType(),
        new VideoAvEpochResult(
            Long.toString(epoch.sourceFirstPts()),
            Long.toString(epoch.sourceTimeBaseNumerator()),
            Long.toString(epoch.sourceTimeBaseDenominator()),
            Long.toString(epoch.ticksPerSecond())),
        new VideoAvWindowResult(
            window.id(),
            window.ordinal(),
            Long.toString(window.startTick()),
            Long.toString(window.endTick()),
            startMs,
            endMs,
            video == null
                ? null
                : new VideoAvVideoResult(
                    video.clipSha256(),
                    video.frameCount(),
                    video.framesManifestSha256(),
                    Long.toString(video.firstLocalTick()),
                    Long.toString(video.endLocalTick())),
            audio == null
                ? null
                : new VideoAvAudioResult(
                    audio.pcmSha256(),
                    audio.wavSha256(),
                    Long.toString(audio.startSample()),
                    Long.toString(audio.endSample()),
                    audio.sampleRate())),
        proof.facts().stream()
            .map(
                f ->
                    new VideoAvFactResult(
                        f.id(),
                        f.text(),
                        f.requirement().name(),
                        f.visualContribution(),
                        f.audioContribution()))
            .toList(),
        proof.factsSha256(),
        publication.analysisModelRevision(),
        POLICY_REVISION,
        "server_window",
        url,
        url + "/content");
  }

  private boolean configurationCurrent() {
    return !closed.get()
        && compiler.configurationCurrent()
        && modelRevision.equals(models.revision())
        && targets.visual().embeddingIdentity().equals(embedding.revision())
        && targets.visual().modelRevision().equals(embedding.revision())
        && targets.audio().embeddingIdentity().equals(embedding.revision())
        && targets.audio().modelRevision().equals(embedding.revision())
        && targets.visual().dimensions() == embedding.dimensions()
        && targets.audio().dimensions() == embedding.dimensions()
        && targets.visual().projectionIdentity().equals(visualProjection.identity())
        && targets.audio().projectionIdentity().equals(audioProjection.identity())
        && profile.equals(
            VideoAvProfile.fingerprint(
                targets, modelRevision, compiler.decoderRevision(), compiler.chunkSeconds()));
  }

  private void requireConfiguration() {
    if (!configurationCurrent()) {
      throw new ApplicationException(FailureKind.CONFLICT, "configuration_changed", "视频音画服务配置已变化。");
    }
  }

  private void current(VideoAvScope scope, Processing processing) {
    processing.check();
    requireConfiguration();
    if (!store.transaction(() -> repository.current(scope, targets, profile))) {
      throw changed();
    }
    processing.check();
  }

  private static final class Rejected extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private Rejected(String reason) {
      super("VideoAv proof unavailable", null, false, false);
      this.reason = reason;
    }
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "视频音画范围、权限或发布版本已变化。");
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "video_av_answers_unavailable", "视频音画问答暂不可用。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "answer_timeout", "视频音画问答处理超过时限或已中断。");
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
