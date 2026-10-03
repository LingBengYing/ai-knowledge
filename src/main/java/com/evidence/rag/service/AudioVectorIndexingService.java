package com.evidence.rag.service;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioVectorBuildClaim;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import com.evidence.rag.model.domain.AudioVectorSpan;
import com.evidence.rag.model.domain.AudioVectorState;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedAudioEvidence;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ProcessAudioVectorIndexer;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import java.util.function.Supplier;

/**
 * Explicit whole-source audio vector publication. Decode/model work never holds a SQL transaction.
 */
public final class AudioVectorIndexingService {
  private final SqliteAuthorityStore store;
  private MilvusRestProjection.Settings cleanupProjection;
  private final AudioVectorRepository vectors;
  private final EvidenceRepository evidence;
  private final ManagementRepository management;
  private final IngestionRepository ingestion;
  private final DocumentPermissionPolicy permissions;
  private final IndexTarget textTarget;
  private final IndexTarget audioTarget;
  private final AudioDecoder decoder;
  private final String decoderRevision;
  private final Duration budget;
  private final Supplier<IndexTarget> profiles;
  private final BiFunction<AudioVectorBuildClaim, Duration, AudioVectorReceipt> worker;
  private final Semaphore capacity;
  private final Set<String> active = ConcurrentHashMap.newKeySet();

  public AudioVectorIndexingService(
      SqliteAuthorityStore store,
      AudioVectorRepository vectors,
      EvidenceRepository evidence,
      ManagementRepository management,
      IngestionRepository ingestion,
      DocumentPermissionPolicy permissions,
      IndexTarget textTarget,
      IndexTarget audioTarget,
      GeminiAudioEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      AudioDecoder decoder,
      Duration processingBudget,
      int maxConcurrent) {
    this(
        store,
        vectors,
        evidence,
        management,
        ingestion,
        permissions,
        textTarget,
        audioTarget,
        decoder,
        processingBudget,
        maxConcurrent,
        () -> audioTarget,
        (claim, remaining) -> {
          try (var process = new ProcessAudioVectorIndexer(models, projection, remaining)) {
            return process.index(claim);
          }
        });
    this.cleanupProjection = projection;
    try (var metadata = new GeminiAudioEmbeddingModels(models);
        var process = new ProcessAudioVectorIndexer(models, projection, processingBudget)) {
      if (!metadata.revision().equals(audioTarget.embeddingIdentity())
          || !metadata.revision().equals(audioTarget.modelRevision())
          || metadata.dimensions() != audioTarget.dimensions()
          || !metadata.decoderRevision().equals(decoderRevision)
          || !projection.identity().equals(audioTarget.projectionIdentity())
          || !projection.embeddingIdentity().equals(audioTarget.embeddingIdentity())) {
        throw ModelValues.invalid();
      }
    }
  }

  // Trusted deterministic worker Adapter for authority tests; production always uses a child JVM.
  AudioVectorIndexingService(
      SqliteAuthorityStore store,
      AudioVectorRepository vectors,
      EvidenceRepository evidence,
      ManagementRepository management,
      IngestionRepository ingestion,
      DocumentPermissionPolicy permissions,
      IndexTarget textTarget,
      IndexTarget audioTarget,
      AudioDecoder decoder,
      Duration processingBudget,
      int maxConcurrent,
      Supplier<IndexTarget> profiles,
      BiFunction<AudioVectorBuildClaim, Duration, AudioVectorReceipt> worker) {
    if (store == null
        || vectors == null
        || evidence == null
        || management == null
        || ingestion == null
        || permissions == null
        || textTarget == null
        || audioTarget == null
        || decoder == null
        || profiles == null
        || worker == null
        || processingBudget == null
        || processingBudget.compareTo(Duration.ofMillis(10)) < 0
        || processingBudget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 8
        || textTarget.projectionIdentity().equals(audioTarget.projectionIdentity())) {
      throw ModelValues.invalid();
    }
    this.store = store;
    this.vectors = vectors;
    this.evidence = evidence;
    this.management = management;
    this.ingestion = ingestion;
    this.permissions = permissions;
    this.textTarget = textTarget;
    this.audioTarget = audioTarget;
    this.decoder = decoder;
    this.decoderRevision = decoder.revision();
    ModelValues.identifier(decoderRevision, 200);
    this.budget = processingBudget;
    this.profiles = profiles;
    this.worker = worker;
    capacity = new Semaphore(maxConcurrent);
  }

  public AudioVectorState get(Actor actor, String documentId) {
    require(actor, documentId);
    currentProfile();
    return store.transaction(() -> state(actor, freeze(actor, documentId, false)));
  }

  public AudioVectorState build(Actor actor, String documentId) {
    try (var operation = store.operationGate().enter()) {
      return buildWithinOperation(actor, documentId);
    }
  }

  private AudioVectorState buildWithinOperation(Actor actor, String documentId) {
    require(actor, documentId);
    long started = System.nanoTime();
    check(started);
    var initial =
        store.transaction(
            () -> {
              check(started);
              return state(actor, freeze(actor, documentId, true));
            });
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
              () -> {
                check(started);
                var frozen = freeze(actor, documentId, true);
                return new Capture(frozen, state(actor, frozen));
              });
      var frozen = captured.frozen();
      if (captured.state().publication() != null) {
        return captured.state();
      }
      var decoded = decode(frozen, started);
      check(started);
      var claim = claim(actor, frozen, decoded);
      if (cleanupProjection != null) {
        store.transaction(
            () -> {
              check(started);
              if (!same(frozen, freeze(actor, documentId, true))) {
                throw stale();
              }
              var registry = new DocumentCleanupRepository(store);
              registry.registerProjectionAttempt(
                  new ProjectionAttempt(
                      documentId,
                      actor.workspaceId(),
                      claim.basePublication().sourceRevisionId(),
                      claim.basePublication().sourceSha256(),
                      claim.vectorGenerationId(),
                      "audio",
                      MilvusProjectionCleanup.qualified(cleanupProjection),
                      false));
              registry.markProjectionWriteIssued(claim.vectorGenerationId(), "audio");
              return null;
            });
      }
      AudioVectorReceipt receipt;
      try {
        receipt = worker.apply(claim, remaining(started));
      } catch (ProcessAudioVectorIndexer.Failure safe) {
        throw failure(safe.code());
      } catch (ApplicationException safe) {
        throw safe;
      } catch (RuntimeException unsafe) {
        throw failure("audio_vector_unavailable");
      }
      check(started);
      verify(claim, receipt);
      return store.transaction(
          () -> {
            check(started);
            var current = freeze(actor, documentId, true);
            if (!same(frozen, current)) {
              throw stale();
            }
            verify(claim, receipt);
            var entries = new ArrayList<AudioVectorEntry>();
            for (int i = 0; i < claim.spans().size(); i++) {
              var span = claim.spans().get(i);
              var saved = receipt.entries().get(i);
              entries.add(
                  new AudioVectorEntry(
                      span.audioEvidenceId(),
                      span.basePhysicalSegmentId(),
                      saved.physicalSegmentId(),
                      span.ordinal(),
                      span.waveform().startSample(),
                      span.waveform().endSample(),
                      span.waveform().pcmSha256(),
                      saved.entrySha256()));
            }
            var publication =
                new AudioVectorPublication(
                    UUID.randomUUID().toString(),
                    frozen.base(),
                    audioTarget,
                    claim.vectorGenerationId(),
                    decoderRevision,
                    entries,
                    receipt.verified().manifestSha256(),
                    Instant.now().toString());
            check(started);
            vectors.insert(publication);
            var result = state(actor, current);
            if (result.publication() == null
                || !result.publication().equals(publication)
                || !same(current, freeze(actor, documentId, true))) {
              throw stale();
            }
            check(started);
            return result;
          });
    } finally {
      if (acquired) {
        capacity.release();
      }
      active.remove(documentId);
    }
  }

  private Frozen freeze(Actor actor, String documentId, boolean edit) {
    currentProfile();
    permissions.require(management.currentRole(actor, documentId), edit);
    var selected = DocumentSelection.selected(List.of(documentId));
    var publications = evidence.findActivePublications(actor, selected);
    if (publications.size() != 1) {
      throw ModelValues.notFound();
    }
    var base = publications.getFirst();
    if (!base.target().equals(textTarget)) {
      throw stale();
    }
    var compilation =
        ingestion.findAudioCompilation(base.sourceRevisionId()).orElseThrow(ModelValues::notFound);
    if (!compilation.sourceSha256().equals(base.sourceSha256())
        || !compilation.compilerRevision().equals(base.parserRevision())
        || !compilation.decoderRevision().equals(decoderRevision)) {
      throw stale();
    }
    var speech =
        AudioEvidence.fromCompilation(base.sourceRevisionId(), compilation).stream()
            .filter(span -> span.indexOrdinal() != null)
            .toList();
    if (speech.size() != base.segmentCount()) {
      throw stale();
    }
    var ids =
        speech.stream()
            .map(
                span ->
                    RetrievalProjection.physicalSegmentId(base.projectionGenerationId(), span.id()))
            .toList();
    var sources =
        evidence.findPublishedAudioEvidence(new EvidenceScope(actor, selected, List.of(base)), ids);
    if (sources.size() != speech.size()) {
      throw stale();
    }
    var byId = new TreeMap<String, PublishedAudioEvidence>();
    for (var source : sources) {
      if (byId.put(source.span().id(), source) != null) {
        throw stale();
      }
    }
    var ordered = new ArrayList<PublishedAudioEvidence>();
    for (var span : speech) {
      var source = byId.get(span.id());
      if (source == null
          || !source.publication().equals(base)
          || !source.span().equals(span)
          || !source
              .physicalSegmentId()
              .equals(
                  RetrievalProjection.physicalSegmentId(
                      base.projectionGenerationId(), span.id()))) {
        throw stale();
      }
      ordered.add(source);
    }
    var first = ordered.getFirst();
    byte[] original = evidence.findAudioOriginal(actor, base, AudioInput.MAX_BYTES);
    if (original == null || !ModelValues.sha256(original).equals(base.sourceSha256())) {
      throw ModelValues.notFound();
    }
    for (var source : ordered) {
      if (!source.filename().equals(first.filename())
          || !source.mediaType().equals(first.mediaType())) {
        throw stale();
      }
    }
    try {
      AudioInput.validateEnvelope(first.filename(), first.mediaType(), original);
    } catch (TextParser.Failure invalid) {
      throw ModelValues.notFound();
    }
    return new Frozen(
        base, compilation, List.copyOf(ordered), first.filename(), first.mediaType(), original);
  }

  private AudioVectorState state(Actor actor, Frozen frozen) {
    currentProfile();
    var saved = vectors.findPublications(actor.workspaceId(), List.of(frozen.base()), audioTarget);
    if (saved.size() > 1) {
      throw stale();
    }
    if (saved.isEmpty()) {
      return new AudioVectorState(frozen.base(), audioTarget, null);
    }
    var publication = saved.getFirst();
    if (!publication.basePublication().equals(frozen.base())
        || !publication.target().equals(audioTarget)
        || !publication.decoderRevision().equals(decoderRevision)
        || publication.entries().size() != frozen.speech().size()) {
      throw stale();
    }
    var digests = new TreeMap<String, String>();
    for (int i = 0; i < frozen.speech().size(); i++) {
      var source = frozen.speech().get(i);
      var span = source.span();
      var entry = publication.entries().get(i);
      String id =
          RetrievalProjection.physicalSegmentId(publication.vectorGenerationId(), span.id());
      long maximum = span.endMs() * 16;
      boolean last = span.ordinal() == frozen.compilation().spans().size() - 1;
      if (!entry.audioEvidenceId().equals(span.id())
          || !entry.basePhysicalSegmentId().equals(source.physicalSegmentId())
          || !entry.vectorPhysicalSegmentId().equals(id)
          || entry.ordinal() != span.ordinal()
          || entry.startSample() != span.startMs() * 16
          || (!last && entry.endSample() != maximum)
          || (last && (entry.endSample() <= maximum - 16 || entry.endSample() > maximum))) {
        throw stale();
      }
      digests.put(id, entry.entrySha256());
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(),
            frozen.base().documentId(),
            publication.vectorGenerationId(),
            digests);
    if (!manifest.sha256().equals(publication.manifestSha256())) {
      throw stale();
    }
    return new AudioVectorState(frozen.base(), audioTarget, publication);
  }

  private AudioVectorBuildClaim claim(Actor actor, Frozen frozen, DecodedAudio decoded) {
    if (decoded == null
        || !decoded.sourceSha256().equals(frozen.base().sourceSha256())
        || !decoded.decoderRevision().equals(decoderRevision)
        || decoded.durationMs() != frozen.compilation().durationMs()) {
      throw stale();
    }
    byte[] pcm = decoded.pcm();
    long samples = pcm.length / 2;
    var spans = new ArrayList<AudioVectorSpan>();
    for (var source : frozen.speech()) {
      var span = source.span();
      long from = span.startMs() * 16;
      long to =
          span.ordinal() == frozen.compilation().spans().size() - 1 ? samples : span.endMs() * 16;
      if (from < 0 || to <= from || to > samples || to - from > 480000) {
        throw stale();
      }
      var waveform =
          new AudioWaveform(
              frozen.base().sourceSha256(),
              decoderRevision,
              from,
              to,
              Arrays.copyOfRange(pcm, (int) from * 2, (int) to * 2));
      spans.add(
          new AudioVectorSpan(
              span.id(),
              source.physicalSegmentId(),
              span.ordinal(),
              span.startMs(),
              span.endMs(),
              waveform));
    }
    return new AudioVectorBuildClaim(
        actor, frozen.base(), audioTarget, UUID.randomUUID().toString(), decoderRevision, spans);
  }

  private DecodedAudio decode(Frozen frozen, long started) {
    var reserved =
        LibraryOperationGate.protectCurrent(
            () -> {
              return decoder.decode(frozen.filename(), frozen.mediaType(), frozen.original());
            });
    var task = new FutureTask<>(reserved);
    var thread = Thread.ofVirtual().name("audio-vector-decode").unstarted(task);
    try {
      thread.start();
    } catch (RuntimeException | Error failedStart) {
      reserved.close();
      throw failedStart;
    }
    try {
      return task.get(remaining(started).toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException expired) {
      cancelDecoder(thread);
      throw failure("audio_vector_timeout");
    } catch (InterruptedException interrupted) {
      try {
        cancelDecoder(thread);
      } finally {
        Thread.currentThread().interrupt();
      }
      throw failure("audio_vector_interrupted");
    } catch (ExecutionException unsafe) {
      throw failure("audio_vector_unavailable");
    } catch (RuntimeException unsafe) {
      cancelDecoder(thread);
      throw unsafe;
    } finally {
      reserved.close();
    }
  }

  private static void cancelDecoder(Thread thread) {
    boolean interrupted = Thread.interrupted();
    thread.interrupt();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    try {
      while (thread.isAlive()) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) {
          throw failure("audio_vector_unavailable");
        }
        try {
          if (!thread.join(Duration.ofNanos(remaining))) {
            throw failure("audio_vector_unavailable");
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

  private static void verify(AudioVectorBuildClaim claim, AudioVectorReceipt receipt) {
    try {
      if (receipt == null || receipt.entries().size() != claim.spans().size()) {
        throw stale();
      }
      var digests = new TreeMap<String, String>();
      for (int i = 0; i < claim.spans().size(); i++) {
        var span = claim.spans().get(i);
        var saved = receipt.entries().get(i);
        String id =
            RetrievalProjection.physicalSegmentId(
                claim.vectorGenerationId(), span.audioEvidenceId());
        var entry =
            new RetrievalProjection.Entry(
                id,
                claim.actor().workspaceId(),
                claim.basePublication().documentId(),
                claim.vectorGenerationId(),
                span.waveform().pcmSha256(),
                saved.vector());
        String digest = RetrievalProjection.entryDigest(entry);
        if (!id.equals(saved.physicalSegmentId())
            || saved.vector().size() != claim.target().dimensions()
            || !digest.equals(saved.entrySha256())) {
          throw stale();
        }
        digests.put(id, digest);
      }
      var manifest =
          new RetrievalProjection.RevisionManifest(
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              digests);
      if (!receipt.verified().projectionIdentity().equals(claim.target().projectionIdentity())
          || !receipt.verified().manifestSha256().equals(manifest.sha256())
          || receipt.verified().segmentCount() != claim.spans().size()) {
        throw stale();
      }
    } catch (RuntimeException invalid) {
      throw stale();
    }
  }

  private void currentProfile() {
    if (!audioTarget.equals(profiles.get()) || !decoderRevision.equals(decoder.revision())) {
      throw stale();
    }
  }

  private void check(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw failure("audio_vector_interrupted");
    }
    currentProfile();
    if (System.nanoTime() - started >= budget.toNanos()) {
      throw failure("audio_vector_timeout");
    }
  }

  private Duration remaining(long started) {
    check(started);
    long nanos = budget.toNanos() - (System.nanoTime() - started);
    if (nanos < Duration.ofMillis(10).toNanos()) {
      throw failure("audio_vector_timeout");
    }
    return Duration.ofNanos(nanos);
  }

  private static void require(Actor actor, String documentId) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(documentId, 100);
  }

  private static boolean same(Frozen one, Frozen two) {
    return one.base().equals(two.base())
        && one.compilation().equals(two.compilation())
        && one.speech().equals(two.speech())
        && one.filename().equals(two.filename())
        && one.mediaType().equals(two.mediaType())
        && Arrays.equals(one.original(), two.original());
  }

  private static ApplicationException stale() {
    return new ApplicationException(
        FailureKind.CONFLICT, "audio_vector_stale", "原声、发布版本或向量配置已变化，请重新建立原声向量。");
  }

  private static ApplicationException busy() {
    return new ApplicationException(
        FailureKind.CAPACITY_EXCEEDED, "audio_vector_busy", "原声向量构建已达在途限额。");
  }

  private static ApplicationException failure(String code) {
    if ("audio_vector_timeout".equals(code)) {
      return new ApplicationException(FailureKind.TIMEOUT, code, "原声向量构建超时。");
    }
    if ("audio_vector_interrupted".equals(code) || "audio_vector_closed".equals(code)) {
      return new ApplicationException(FailureKind.UNAVAILABLE, code, "原声向量构建已取消。");
    }
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "audio_vector_unavailable", "原声向量构建未完成安全校验。");
  }

  private record Capture(Frozen frozen, AudioVectorState state) {}

  private record Frozen(
      PublicationVersion base,
      AudioCompilation compilation,
      List<PublishedAudioEvidence> speech,
      String filename,
      String mediaType,
      byte[] original) {
    private Frozen {
      original = original.clone();
    }

    @Override
    public byte[] original() {
      return original.clone();
    }
  }
}
