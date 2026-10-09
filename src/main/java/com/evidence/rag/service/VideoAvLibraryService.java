package com.evidence.rag.service;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VideoAvAudioMetadata;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvPublication;
import com.evidence.rag.model.domain.VideoAvPublishedWindow;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvState;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvVideoMetadata;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import com.evidence.rag.worker.indexing.ProcessVideoAvIndexer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * Raw audiovisual registration and explicit complete publication; remote work never holds
 * authority.
 */
public final class VideoAvLibraryService implements AutoCloseable {
  private final SqliteAuthorityStore store;
  private final MilvusRestProjection.Settings cleanupVisualProjection;
  private final MilvusRestProjection.Settings cleanupAudioProjection;
  private final VideoAvRepository videos;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final VideoAvCompilationService compilation;
  private final VideoAvTargets targets;
  private volatile boolean closed;
  private final String analysisModelRevision;
  private final String profile;
  private final Duration budget;
  private final Semaphore capacity;
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private final BiFunction<VideoAvBuildClaim, Duration, VideoAvReceipt> indexer;

  public VideoAvLibraryService(
      SqliteAuthorityStore store,
      VideoAvRepository videos,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      VideoAvCompilationService compilation,
      VideoAvTargets targets,
      String analysisModelRevision,
      GeminiVideoAvEmbeddingModels.Configuration embeddings,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration budget,
      int maxConcurrent) {
    this(
        store,
        videos,
        management,
        permissions,
        compilation,
        targets,
        analysisModelRevision,
        embeddings,
        visualProjection,
        audioProjection,
        budget,
        maxConcurrent,
        (claim, remaining) -> {
          try (var process =
              new ProcessVideoAvIndexer(embeddings, visualProjection, audioProjection, remaining)) {
            return process.index(claim);
          }
        });
  }

  // Real process and deterministic test Adapter share the full authority/publication behavior.
  VideoAvLibraryService(
      SqliteAuthorityStore store,
      VideoAvRepository videos,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      VideoAvCompilationService compilation,
      VideoAvTargets targets,
      String analysisModelRevision,
      GeminiVideoAvEmbeddingModels.Configuration embeddings,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration budget,
      int maxConcurrent,
      BiFunction<VideoAvBuildClaim, Duration, VideoAvReceipt> indexer) {
    if (store == null
        || videos == null
        || management == null
        || permissions == null
        || compilation == null
        || targets == null
        || analysisModelRevision == null
        || embeddings == null
        || visualProjection == null
        || audioProjection == null
        || indexer == null
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 2
        || !matches(targets.visual(), embeddings, visualProjection)
        || !matches(targets.audio(), embeddings, audioProjection)
        || !visualProjection.workspaceId().equals(audioProjection.workspaceId())
        || !compilation.decoderRevision().equals(embeddings.decoderRevision())) {
      throw ModelValues.invalid();
    }
    this.store = store;
    this.cleanupVisualProjection = visualProjection;
    this.cleanupAudioProjection = audioProjection;
    this.videos = videos;
    this.management = management;
    this.permissions = permissions;
    this.compilation = compilation;
    this.targets = targets;
    ModelValues.indexIdentity(analysisModelRevision);
    this.analysisModelRevision = analysisModelRevision;
    this.profile =
        VideoAvProfile.fingerprint(
            targets,
            analysisModelRevision,
            compilation.decoderRevision(),
            compilation.chunkSeconds());
    this.budget = budget;
    this.capacity = new Semaphore(maxConcurrent);
    this.indexer = indexer;
  }

  public VideoAvTargets targets() {
    return targets;
  }

  public String profileFingerprint() {
    return profile;
  }

  public String analysisModelRevision() {
    return analysisModelRevision;
  }

  public boolean configurationCurrent() {
    return !closed && compilation.configurationCurrent();
  }

  public DocumentOriginal upload(Actor actor, String filename, String mime, byte[] content) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    currentProfile();
    try {
      VideoInput.validateEnvelope(filename, mime, content);
    } catch (TextParser.Failure unsupported) {
      throw new ApplicationException(
          FailureKind.UNSUPPORTED_MEDIA, "unsupported_document", "仅接受受支持的完整原视频文件。");
    }
    var original =
        new DocumentOriginal(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            filename,
            "video",
            VideoInput.canonicalMime(filename),
            ModelValues.sha256(content),
            content.length,
            content);
    return store.transaction(
        () -> {
          currentProfile();
          if (new IngestionRepository(store).storedBytes(actor.workspaceId())
              > 256L * 1024 * 1024 - content.length) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_quota_exceeded", "当前组织的待处理任务或原文件存储已达到开发配额。");
          }
          String now = Instant.now().toString();
          management.insertDocument(
              actor,
              new SyntheticDocument(
                  original.documentId(),
                  filename,
                  "video",
                  original.mediaType(),
                  original.revisionId(),
                  original.sourceSha256(),
                  original.sizeBytes()),
              now);
          management.insertGrant(original.documentId(), actor.principalId(), "owner");
          videos.insertOriginal(original, now);
          management.insertAudit(
              AuditEventEntity.create(
                  actor,
                  original.documentId(),
                  "video_av_upload",
                  null,
                  Map.of(
                      "source_sha256", original.sourceSha256(), "size_bytes", original.sizeBytes()),
                  Set.of("source_sha256", "size_bytes")));
          return original;
        });
  }

  public VideoAvState get(Actor actor, String documentId) {
    require(actor, documentId);
    return store.transaction(() -> state(actor, original(actor, documentId, false)));
  }

  public VideoAvState build(Actor actor, String documentId) {
    try (var operation = store.operationGate().enter()) {
      return buildWithinOperation(actor, documentId, null);
    }
  }

  public VideoAvState buildReplacement(Actor actor, String documentId, String replacementId) {
    require(actor, documentId);
    ModelValues.identifier(replacementId, 100);
    try (var operation = store.operationGate().enter()) {
      return buildWithinOperation(actor, documentId, replacementId);
    }
  }

  private VideoAvState buildWithinOperation(Actor actor, String documentId, String replacementId) {
    require(actor, documentId);
    long started = System.nanoTime();
    var initial =
        store.transaction(
            () ->
                stateForBuild(
                    actor, originalForBuild(actor, documentId, replacementId), replacementId));
    if (initial.publication() != null) {
      return initial;
    }
    if (!active.add(documentId)) {
      throw busy();
    }
    boolean acquired = false;
    try {
      acquired = capacity.tryAcquire();
      if (!acquired) {
        throw busy();
      }
      var captured =
          store.transaction(
              () ->
                  stateForBuild(
                      actor, originalForBuild(actor, documentId, replacementId), replacementId));
      if (captured.publication() != null) {
        return captured;
      }
      if (replacementId != null) {
        store.transaction(
            () -> {
              new DocumentUpdateRepository(store)
                  .markIndexing(replacementId, Instant.now().toString());
              return null;
            });
      }
      DocumentOriginal frozen = captured.original();
      var compiled =
          compilation.compile(
              frozen,
              () -> {
                check(started);
                return store.transaction(
                    () -> same(frozen, originalForBuild(actor, documentId, replacementId)));
              });
      check(started);
      var claim =
          new VideoAvBuildClaim(
              actor,
              frozen,
              targets,
              UUID.randomUUID().toString(),
              analysisModelRevision,
              compilation.decoderRevision(),
              compilation.chunkSeconds(),
              compiled,
              profile);
      store.transaction(
          () -> {
            check(started);
            if (!same(frozen, originalForBuild(actor, documentId, replacementId))) {
              throw stale();
            }
            java.util.function.Supplier<Void> register =
                () -> {
                  var registry = new DocumentCleanupRepository(store);
                  if (compiled.windows().stream().anyMatch(window -> window.video() != null)) {
                    registry.registerProjectionAttempt(
                        new ProjectionAttempt(
                            documentId,
                            actor.workspaceId(),
                            frozen.revisionId(),
                            frozen.sourceSha256(),
                            claim.generationId(),
                            "video_av_visual",
                            MilvusProjectionCleanup.qualified(cleanupVisualProjection),
                            false));
                    registry.markProjectionWriteIssued(claim.generationId(), "video_av_visual");
                  }
                  if (compiled.windows().stream().anyMatch(window -> window.audio() != null)) {
                    registry.registerProjectionAttempt(
                        new ProjectionAttempt(
                            documentId,
                            actor.workspaceId(),
                            frozen.revisionId(),
                            frozen.sourceSha256(),
                            claim.generationId(),
                            "video_av_audio",
                            MilvusProjectionCleanup.qualified(cleanupAudioProjection),
                            false));
                    registry.markProjectionWriteIssued(claim.generationId(), "video_av_audio");
                  }
                  return null;
                };
            return replacementId == null
                ? register.get()
                : new DocumentUpdateRepository(store).withCandidateSource(replacementId, register);
          });
      var receipt = execute(claim, started, replacementId);
      try {
        ProcessVideoAvIndexer.verify(claim, receipt);
      } catch (RuntimeException invalid) {
        throw stale();
      }
      return store.transaction(
          () -> {
            check(started);
            var current = originalForBuild(actor, documentId, replacementId);
            if (!same(frozen, current)) {
              throw stale();
            }
            var existing = stateForBuild(actor, current, replacementId);
            if (existing.publication() != null) {
              return existing;
            }
            var byKey = new HashMap<String, VideoAvReceipt.Entry>();
            for (var entry : receipt.entries()) {
              byKey.put(entry.route() + ":" + entry.windowId(), entry);
            }
            var published = new ArrayList<VideoAvPublishedWindow>();
            for (var input : compiled.windows()) {
              var visual = byKey.get(VideoAvRoute.VISUAL + ":" + input.id());
              var audio = byKey.get(VideoAvRoute.AUDIO + ":" + input.id());
              var clip = input.video();
              var wave = input.audio();
              published.add(
                  new VideoAvPublishedWindow(
                      input.id(),
                      input.ordinal(),
                      input.startTick(),
                      input.endTick(),
                      clip == null
                          ? null
                          : new VideoAvVideoMetadata(
                              clip.sha256(),
                              clip.frameCount(),
                              clip.framesManifestSha256(),
                              clip.firstLocalTick(),
                              clip.endLocalTick()),
                      wave == null
                          ? null
                          : new VideoAvAudioMetadata(
                              wave.pcmSha256(),
                              ModelValues.sha256(wave.wav()),
                              wave.startSample(),
                              wave.endSample(),
                              16000),
                      visual == null ? null : visual.physicalSegmentId(),
                      visual == null ? null : visual.entrySha256(),
                      audio == null ? null : audio.physicalSegmentId(),
                      audio == null ? null : audio.entrySha256()));
            }
            var publication =
                new VideoAvPublication(
                    claim.generationId(),
                    actor.workspaceId(),
                    documentId,
                    frozen.revisionId(),
                    frozen.sourceSha256(),
                    frozen.filename(),
                    frozen.mediaType(),
                    frozen.sizeBytes(),
                    compiled.epoch(),
                    compiled.durationTick(),
                    compiled.hasAudio(),
                    compiled.decoderRevision(),
                    analysisModelRevision,
                    profile,
                    targets.visual(),
                    targets.audio(),
                    compilation.chunkSeconds(),
                    VideoAvProfile.windowManifestSha256(compiled),
                    published,
                    receipt.visualReceipt(),
                    receipt.audioReceipt(),
                    Instant.now().toEpochMilli());
            java.util.function.Supplier<VideoAvState> persist =
                () -> {
                  videos.insertPublication(publication);
                  if (replacementId != null) {
                    new DocumentUpdateRepository(store)
                        .activate(replacementId, publication.id(), Instant.now().toString());
                  }
                  management.insertAudit(
                      AuditEventEntity.create(
                          actor,
                          documentId,
                          "video_av_index",
                          null,
                          Map.of(
                              "publication_id",
                              publication.id(),
                              "manifest_sha256",
                              publication.manifestSha256()),
                          Set.of("publication_id", "manifest_sha256")));
                  var result = state(actor, original(actor, documentId, true));
                  if (!publication.equals(result.publication())
                      || !same(frozen, result.original())) {
                    throw stale();
                  }
                  check(started);
                  return result;
                };
            return replacementId == null
                ? persist.get()
                : new DocumentUpdateRepository(store).withCandidateSource(replacementId, persist);
          });
    } catch (TextParser.Failure parser) {
      failReplacement(replacementId);
      throw failure(
          parser.code().equals("parser_timeout")
              ? "video_av_index_timeout"
              : "video_av_index_unavailable");
    } catch (RuntimeException failed) {
      failReplacement(replacementId);
      throw failed;
    } finally {
      if (acquired) {
        capacity.release();
      }
      active.remove(documentId);
    }
  }

  private VideoAvReceipt execute(VideoAvBuildClaim claim, long started, String replacementId) {
    var reserved =
        LibraryOperationGate.protectCurrent(
            () -> {
              return indexer.apply(claim, remaining(started));
            });
    var task = new FutureTask<>(reserved);
    var thread = Thread.ofVirtual().name("video-av-library-index").unstarted(task);
    try {
      thread.start();
    } catch (RuntimeException | Error failedStart) {
      reserved.close();
      throw failedStart;
    }
    try {
      while (true) {
        check(started);
        if (!store.transaction(
            () ->
                same(
                    claim.original(),
                    originalForBuild(
                        claim.actor(), claim.original().documentId(), replacementId)))) {
          throw stale();
        }
        try {
          return task.get(
              Math.min(remaining(started).toNanos(), TimeUnit.MILLISECONDS.toNanos(25)),
              TimeUnit.NANOSECONDS);
        } catch (TimeoutException tick) {
          check(started);
        }
      }
    } catch (InterruptedException interrupted) {
      stop(thread);
      Thread.currentThread().interrupt();
      throw failure("video_av_index_interrupted");
    } catch (ExecutionException problem) {
      if (problem.getCause() instanceof ProcessVideoAvIndexer.Failure safe) {
        throw failure(safe.code());
      }
      throw failure("video_av_index_unavailable");
    } catch (RuntimeException invalid) {
      stop(thread);
      throw invalid;
    } finally {
      reserved.close();
    }
  }

  private void failReplacement(String replacementId) {
    if (replacementId == null) {
      return;
    }
    store.transaction(
        () -> {
          var updates = new DocumentUpdateRepository(store);
          var replacement = updates.find(replacementId).orElse(null);
          if (replacement != null
              && "indexing".equals(replacement.state())
              && updates
                  .current(replacement.documentId())
                  .map(value -> replacementId.equals(value.id()))
                  .orElse(false)) {
            updates.markFailed(replacementId, Instant.now().toString());
          }
          return null;
        });
  }

  private DocumentOriginal originalForBuild(Actor actor, String documentId, String replacementId) {
    if (replacementId == null) {
      return original(actor, documentId, true);
    }
    currentProfile();
    permissions.require(management.currentRole(actor, documentId), true);
    var updates = new DocumentUpdateRepository(store);
    var replacement = updates.find(replacementId).orElseThrow(ModelValues::notFound);
    if (!documentId.equals(replacement.documentId())
        || !"video_av".equals(replacement.pipeline())
        || !updates.sourceCurrent(replacementId)) {
      throw stale();
    }
    return updates
        .original(documentId, replacement.candidateRevisionId())
        .orElseThrow(ModelValues::notFound);
  }

  private VideoAvState stateForBuild(Actor actor, DocumentOriginal original, String replacementId) {
    return replacementId == null
        ? state(actor, original)
        : new VideoAvState(original, targets.visual(), targets.audio(), null);
  }

  private DocumentOriginal original(Actor actor, String documentId, boolean edit) {
    currentProfile();
    permissions.require(management.currentRole(actor, documentId), edit);
    return videos.findOriginal(actor, documentId).orElseThrow(ModelValues::notFound);
  }

  private VideoAvState state(Actor actor, DocumentOriginal original) {
    currentProfile();
    var publication = videos.findPublication(original, targets, profile).orElse(null);
    if (publication != null && !actor.workspaceId().equals(publication.workspaceId())) {
      throw stale();
    }
    return new VideoAvState(original, targets.visual(), targets.audio(), publication);
  }

  private static boolean matches(
      IndexTarget target,
      GeminiVideoAvEmbeddingModels.Configuration embeddings,
      MilvusRestProjection.Settings projection) {
    return target.embeddingIdentity().equals(embeddings.revision())
        && target.modelRevision().equals(embeddings.revision())
        && target.projectionIdentity().equals(projection.identity())
        && target.dimensions() == embeddings.dimensions()
        && target.dimensions() == projection.dimension()
        && projection.embeddingIdentity().equals(embeddings.revision());
  }

  @Override
  public void close() {
    closed = true;
    compilation.close();
  }

  private void currentProfile() {
    if (!configurationCurrent()) {
      throw stale();
    }
  }

  private void check(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw failure("video_av_index_interrupted");
    }
    currentProfile();
    if (System.nanoTime() - started >= budget.toNanos()) {
      throw failure("video_av_index_timeout");
    }
  }

  private Duration remaining(long started) {
    check(started);
    long nanos = budget.toNanos() - (System.nanoTime() - started);
    if (nanos < Duration.ofMillis(10).toNanos()) {
      throw failure("video_av_index_timeout");
    }
    return Duration.ofNanos(nanos);
  }

  private static void stop(Thread thread) {
    boolean interrupted = Thread.interrupted();
    thread.interrupt();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    try {
      while (thread.isAlive()) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) {
          throw failure("video_av_index_unavailable");
        }
        try {
          if (!thread.join(Duration.ofNanos(remaining))) {
            throw failure("video_av_index_unavailable");
          }
        } catch (InterruptedException repeated) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static boolean same(DocumentOriginal one, DocumentOriginal two) {
    return one.documentId().equals(two.documentId())
        && one.revisionId().equals(two.revisionId())
        && one.filename().equals(two.filename())
        && one.mediaType().equals(two.mediaType())
        && one.sourceSha256().equals(two.sourceSha256())
        && one.sizeBytes() == two.sizeBytes()
        && Arrays.equals(one.content(), two.content());
  }

  private static void require(Actor actor, String documentId) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(documentId, 100);
  }

  private static ApplicationException stale() {
    return new ApplicationException(
        FailureKind.CONFLICT, "video_av_index_stale", "视频音画原文件、权限或配置已变化，请重新建立视频音画索引。");
  }

  private static ApplicationException busy() {
    return new ApplicationException(
        FailureKind.CAPACITY_EXCEEDED, "video_av_index_busy", "视频音画索引构建已达在途限额。");
  }

  private static ApplicationException failure(String code) {
    if ("video_av_index_timeout".equals(code)) {
      return new ApplicationException(FailureKind.TIMEOUT, code, "视频音画索引构建超时。");
    }
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "video_av_index_unavailable", "视频音画索引构建未完成安全校验。");
  }
}
