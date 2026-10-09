package com.evidence.rag.service;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundReceipt;
import com.evidence.rag.model.domain.SoundSpan;
import com.evidence.rag.model.domain.SoundState;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ProcessSoundIndexer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/** Raw sound registration and explicit complete publication; remote work never holds authority. */
public final class SoundLibraryService {
  private final SqliteAuthorityStore store;
  private final MilvusRestProjection.Settings cleanupProjection;
  private final SoundRepository sounds;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final SoundCompilationService compilation;
  private final IndexTarget target;
  private final String soundModelRevision;
  private final String profile;
  private final Duration budget;
  private final Semaphore capacity;
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private final BiFunction<SoundBuildClaim, Duration, SoundReceipt> indexer;

  public SoundLibraryService(
      SqliteAuthorityStore store,
      SoundRepository sounds,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      SoundCompilationService compilation,
      IndexTarget target,
      GeminiSoundModels.Configuration models,
      GeminiSoundEmbeddingModels.Configuration embeddings,
      MilvusRestProjection.Settings projection,
      Duration budget,
      int maxConcurrent) {
    this(
        store,
        sounds,
        management,
        permissions,
        compilation,
        target,
        models,
        embeddings,
        projection,
        budget,
        maxConcurrent,
        (claim, remaining) -> {
          try (var process = new ProcessSoundIndexer(models, embeddings, projection, remaining)) {
            return process.index(claim);
          }
        });
  }

  // Real process and deterministic test Adapter share the full authority/publication behavior.
  SoundLibraryService(
      SqliteAuthorityStore store,
      SoundRepository sounds,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      SoundCompilationService compilation,
      IndexTarget target,
      GeminiSoundModels.Configuration models,
      GeminiSoundEmbeddingModels.Configuration embeddings,
      MilvusRestProjection.Settings projection,
      Duration budget,
      int maxConcurrent,
      BiFunction<SoundBuildClaim, Duration, SoundReceipt> indexer) {
    if (store == null
        || sounds == null
        || management == null
        || permissions == null
        || compilation == null
        || target == null
        || models == null
        || embeddings == null
        || projection == null
        || indexer == null
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 2
        || !target.embeddingIdentity().equals(embeddings.revision())
        || !target.modelRevision().equals(embeddings.revision())
        || !target.projectionIdentity().equals(projection.identity())
        || target.dimensions() != embeddings.dimensions()
        || target.dimensions() != projection.dimension()
        || !projection.embeddingIdentity().equals(embeddings.revision())
        || !compilation.decoderRevision().equals(embeddings.decoderRevision())) {
      throw ModelValues.invalid();
    }
    this.store = store;
    this.cleanupProjection = projection;
    this.sounds = sounds;
    this.management = management;
    this.permissions = permissions;
    this.compilation = compilation;
    this.target = target;
    this.soundModelRevision = models.revision();
    this.profile =
        SoundProfile.fingerprint(
            target, soundModelRevision, compilation.decoderRevision(), compilation.chunkSeconds());
    this.budget = budget;
    this.capacity = new Semaphore(maxConcurrent);
    this.indexer = indexer;
  }

  public IndexTarget target() {
    return target;
  }

  public String profileFingerprint() {
    return profile;
  }

  public String soundModelRevision() {
    return soundModelRevision;
  }

  public boolean configurationCurrent() {
    return compilation.configurationCurrent();
  }

  public DocumentOriginal upload(Actor actor, String filename, String mime, byte[] content) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    currentProfile();
    try {
      AudioInput.validateEnvelope(filename, mime, content);
    } catch (TextParser.Failure unsupported) {
      throw new ApplicationException(
          FailureKind.UNSUPPORTED_MEDIA, "unsupported_document", "仅接受受支持的完整原音频文件。");
    }
    var original =
        new DocumentOriginal(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            filename,
            "audio",
            AudioInput.canonicalMime(filename),
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
                  "audio",
                  original.mediaType(),
                  original.revisionId(),
                  original.sourceSha256(),
                  original.sizeBytes()),
              now);
          management.insertGrant(original.documentId(), actor.principalId(), "owner");
          sounds.insertOriginal(original, now);
          management.insertAudit(
              AuditEventEntity.create(
                  actor,
                  original.documentId(),
                  "sound_upload",
                  null,
                  Map.of(
                      "source_sha256", original.sourceSha256(), "size_bytes", original.sizeBytes()),
                  Set.of("source_sha256", "size_bytes")));
          return original;
        });
  }

  public SoundState get(Actor actor, String documentId) {
    require(actor, documentId);
    return store.transaction(() -> state(actor, original(actor, documentId, false)));
  }

  public SoundState build(Actor actor, String documentId) {
    try (var operation = store.operationGate().enter()) {
      return buildWithinOperation(actor, documentId, null);
    }
  }

  public SoundState buildReplacement(Actor actor, String documentId, String replacementId) {
    require(actor, documentId);
    ModelValues.identifier(replacementId, 100);
    try (var operation = store.operationGate().enter()) {
      return buildWithinOperation(actor, documentId, replacementId);
    }
  }

  private SoundState buildWithinOperation(Actor actor, String documentId, String replacementId) {
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
      var spans =
          compilation.decode(
              frozen,
              () -> {
                check(started);
                return store.transaction(
                    () -> same(frozen, originalForBuild(actor, documentId, replacementId)));
              });
      check(started);
      var claim =
          new SoundBuildClaim(
              actor,
              frozen,
              target,
              UUID.randomUUID().toString(),
              soundModelRevision,
              compilation.decoderRevision(),
              compilation.chunkSeconds(),
              spans,
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
                  registry.registerProjectionAttempt(
                      new ProjectionAttempt(
                          documentId,
                          actor.workspaceId(),
                          frozen.revisionId(),
                          frozen.sourceSha256(),
                          claim.generationId(),
                          "sound",
                          MilvusProjectionCleanup.qualified(cleanupProjection),
                          false));
                  registry.markProjectionWriteIssued(claim.generationId(), "sound");
                  return null;
                };
            return replacementId == null
                ? register.get()
                : new DocumentUpdateRepository(store).withCandidateSource(replacementId, register);
          });
      var receipt = execute(claim, started, replacementId);
      verify(claim, receipt);
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
            var published = new ArrayList<SoundSpan>();
            for (int i = 0; i < spans.size(); i++) {
              var input = spans.get(i);
              var saved = receipt.entries().get(i);
              published.add(
                  new SoundSpan(
                      input.id(),
                      i,
                      input.waveform().startSample(),
                      input.waveform().endSample(),
                      input.waveform().pcmSha256(),
                      saved.recallText(),
                      saved.physicalSegmentId(),
                      saved.entrySha256()));
            }
            var publication =
                new SoundPublication(
                    UUID.randomUUID().toString(),
                    actor.workspaceId(),
                    documentId,
                    frozen.revisionId(),
                    frozen.sourceSha256(),
                    frozen.filename(),
                    frozen.mediaType(),
                    frozen.sizeBytes(),
                    claim.generationId(),
                    target,
                    soundModelRevision,
                    compilation.decoderRevision(),
                    compilation.chunkSeconds(),
                    spans.getLast().waveform().endSample(),
                    published,
                    receipt.verified().manifestSha256(),
                    profile,
                    Instant.now().toString());
            java.util.function.Supplier<SoundState> persist =
                () -> {
                  sounds.insertPublication(publication);
                  if (replacementId != null) {
                    new DocumentUpdateRepository(store)
                        .activate(replacementId, publication.id(), Instant.now().toString());
                  }
                  management.insertAudit(
                      AuditEventEntity.create(
                          actor,
                          documentId,
                          "sound_index",
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
              ? "sound_index_timeout"
              : "sound_index_unavailable");
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

  private SoundReceipt execute(SoundBuildClaim claim, long started, String replacementId) {
    var reserved =
        LibraryOperationGate.protectCurrent(
            () -> {
              return indexer.apply(claim, remaining(started));
            });
    var task = new FutureTask<>(reserved);
    var thread = Thread.ofVirtual().name("sound-library-index").unstarted(task);
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
      throw failure("sound_index_interrupted");
    } catch (ExecutionException problem) {
      if (problem.getCause() instanceof ProcessSoundIndexer.Failure safe) {
        throw failure(safe.code());
      }
      throw failure("sound_index_unavailable");
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
        || !"sound".equals(replacement.pipeline())
        || !updates.sourceCurrent(replacementId)) {
      throw stale();
    }
    return updates
        .original(documentId, replacement.candidateRevisionId())
        .orElseThrow(ModelValues::notFound);
  }

  private SoundState stateForBuild(Actor actor, DocumentOriginal original, String replacementId) {
    return replacementId == null
        ? state(actor, original)
        : new SoundState(original, target, profile, null);
  }

  private DocumentOriginal original(Actor actor, String documentId, boolean edit) {
    currentProfile();
    permissions.require(management.currentRole(actor, documentId), edit);
    return sounds.findOriginal(actor, documentId).orElseThrow(ModelValues::notFound);
  }

  private SoundState state(Actor actor, DocumentOriginal original) {
    currentProfile();
    var publication = sounds.findPublication(original, target, profile).orElse(null);
    if (publication != null && !actor.workspaceId().equals(publication.workspaceId())) {
      throw stale();
    }
    return new SoundState(original, target, profile, publication);
  }

  private static void verify(SoundBuildClaim claim, SoundReceipt receipt) {
    if (receipt == null || receipt.entries().size() != claim.spans().size()) {
      throw stale();
    }
    var digests = new TreeMap<String, String>();
    for (int i = 0; i < claim.spans().size(); i++) {
      var input = claim.spans().get(i);
      var saved = receipt.entries().get(i);
      String physical = RetrievalProjection.physicalSegmentId(claim.generationId(), input.id());
      var entry =
          new RetrievalProjection.Entry(
              physical,
              claim.actor().workspaceId(),
              claim.original().documentId(),
              claim.generationId(),
              input.waveform().pcmSha256(),
              saved.vector());
      String digest = RetrievalProjection.entryDigest(entry);
      if (!saved.spanId().equals(input.id())
          || !saved.physicalSegmentId().equals(physical)
          || saved.vector().size() != claim.target().dimensions()
          || !saved.entrySha256().equals(digest)) {
        throw stale();
      }
      digests.put(physical, digest);
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.original().documentId(),
            claim.generationId(),
            digests);
    if (!receipt.verified().projectionIdentity().equals(claim.target().projectionIdentity())
        || !receipt.verified().manifestSha256().equals(manifest.sha256())
        || receipt.verified().segmentCount() != claim.spans().size()) {
      throw stale();
    }
  }

  private void currentProfile() {
    if (!configurationCurrent()) {
      throw stale();
    }
  }

  private void check(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw failure("sound_index_interrupted");
    }
    currentProfile();
    if (System.nanoTime() - started >= budget.toNanos()) {
      throw failure("sound_index_timeout");
    }
  }

  private Duration remaining(long started) {
    check(started);
    long nanos = budget.toNanos() - (System.nanoTime() - started);
    if (nanos < Duration.ofMillis(10).toNanos()) {
      throw failure("sound_index_timeout");
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
          throw failure("sound_index_unavailable");
        }
        try {
          if (!thread.join(Duration.ofNanos(remaining))) {
            throw failure("sound_index_unavailable");
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
        FailureKind.CONFLICT, "sound_index_stale", "声音原文件、权限或配置已变化，请重新建立声音索引。");
  }

  private static ApplicationException busy() {
    return new ApplicationException(
        FailureKind.CAPACITY_EXCEEDED, "sound_index_busy", "声音索引构建已达在途限额。");
  }

  private static ApplicationException failure(String code) {
    if ("sound_index_timeout".equals(code)) {
      return new ApplicationException(FailureKind.TIMEOUT, code, "声音索引构建超时。");
    }
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "sound_index_unavailable", "声音索引构建未完成安全校验。");
  }
}
