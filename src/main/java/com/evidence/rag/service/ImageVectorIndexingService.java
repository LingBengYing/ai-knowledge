package com.evidence.rag.service;

import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageVectorBuildClaim;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.ImageVectorReceipt;
import com.evidence.rag.model.domain.ImageVectorState;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ProcessImageVectorIndexer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Explicit original-image vector publication; remote work never holds an authority transaction. */
public final class ImageVectorIndexingService {
  private final SqliteAuthorityStore store;
  private MilvusRestProjection.Settings cleanupProjection;
  private final ImageVectorRepository vectors;
  private final EvidenceRepository evidence;
  private final ManagementRepository management;
  private final IngestionRepository ingestion;
  private final DocumentPermissionPolicy permissions;
  private final IndexTarget textTarget;
  private final IndexTarget imageTarget;
  private final Duration budget;
  private final Supplier<IndexTarget> profiles;
  private final BiFunction<ImageVectorBuildClaim, Duration, ImageVectorReceipt> worker;
  private final Semaphore capacity;
  private final Set<String> active = ConcurrentHashMap.newKeySet();

  public ImageVectorIndexingService(
      SqliteAuthorityStore store,
      ImageVectorRepository vectors,
      EvidenceRepository evidence,
      ManagementRepository management,
      IngestionRepository ingestion,
      DocumentPermissionPolicy permissions,
      IndexTarget textTarget,
      IndexTarget imageTarget,
      SiliconFlowImageEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
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
        imageTarget,
        processingBudget,
        maxConcurrent,
        () -> imageTarget,
        (claim, remaining) -> {
          try (var process = new ProcessImageVectorIndexer(models, projection, remaining)) {
            return process.index(claim);
          }
        });
    this.cleanupProjection = projection;
    try (var metadata = new SiliconFlowImageEmbeddingModels(models);
        var process = new ProcessImageVectorIndexer(models, projection, processingBudget)) {
      if (!metadata.revision().equals(imageTarget.embeddingIdentity())
          || !metadata.revision().equals(imageTarget.modelRevision())
          || metadata.dimensions() != imageTarget.dimensions()
          || !projection.identity().equals(imageTarget.projectionIdentity())
          || !projection.embeddingIdentity().equals(imageTarget.embeddingIdentity())) {
        throw ModelValues.invalid();
      }
    }
  }

  // Trusted deterministic worker Adapter for authority lifecycle tests; production uses the child
  // JVM.
  ImageVectorIndexingService(
      SqliteAuthorityStore store,
      ImageVectorRepository vectors,
      EvidenceRepository evidence,
      ManagementRepository management,
      IngestionRepository ingestion,
      DocumentPermissionPolicy permissions,
      IndexTarget textTarget,
      IndexTarget imageTarget,
      Duration processingBudget,
      int maxConcurrent,
      Supplier<IndexTarget> profiles,
      BiFunction<ImageVectorBuildClaim, Duration, ImageVectorReceipt> worker) {
    if (store == null
        || vectors == null
        || evidence == null
        || management == null
        || ingestion == null
        || permissions == null
        || textTarget == null
        || imageTarget == null
        || profiles == null
        || worker == null
        || processingBudget == null
        || processingBudget.compareTo(Duration.ofMillis(10)) < 0
        || processingBudget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 8
        || textTarget.projectionIdentity().equals(imageTarget.projectionIdentity())) {
      throw ModelValues.invalid();
    }
    this.store = store;
    this.vectors = vectors;
    this.evidence = evidence;
    this.management = management;
    this.ingestion = ingestion;
    this.permissions = permissions;
    this.textTarget = textTarget;
    this.imageTarget = imageTarget;
    this.budget = processingBudget;
    this.profiles = profiles;
    this.worker = worker;
    capacity = new Semaphore(maxConcurrent);
  }

  public ImageVectorState get(Actor actor, String documentId) {
    require(actor, documentId);
    currentProfile();
    return store.transaction(
        () -> {
          var frozen = freeze(actor, documentId, false);
          return state(actor, frozen);
        });
  }

  public ImageVectorState build(Actor actor, String documentId) {
    try (var operation = store.operationGate().enter()) {
      return buildWithinOperation(actor, documentId);
    }
  }

  private ImageVectorState buildWithinOperation(Actor actor, String documentId) {
    require(actor, documentId);
    long started = System.nanoTime();
    check(started);
    var initial =
        store.transaction(
            () -> {
              check(started);
              var frozen = freeze(actor, documentId, true);
              return state(actor, frozen);
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
                var fresh = freeze(actor, documentId, true);
                return new Capture(fresh, state(actor, fresh));
              });
      var frozen = captured.frozen();
      if (captured.state().publication() != null) {
        return captured.state();
      }
      var claim =
          new ImageVectorBuildClaim(
              actor,
              frozen.base(),
              frozen.imageId(),
              frozen.baseId(),
              imageTarget,
              UUID.randomUUID().toString(),
              frozen.original());
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
                      "image",
                      MilvusProjectionCleanup.qualified(cleanupProjection),
                      false));
              registry.markProjectionWriteIssued(claim.vectorGenerationId(), "image");
              return null;
            });
      }
      Duration remaining = remaining(started);
      ImageVectorReceipt receipt;
      try {
        receipt = worker.apply(claim, remaining);
      } catch (ProcessImageVectorIndexer.Failure safe) {
        throw failure(safe.code());
      } catch (ApplicationException safe) {
        throw safe;
      } catch (RuntimeException unsafe) {
        throw failure("image_vector_unavailable");
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
            var publication =
                new ImageVectorPublication(
                    UUID.randomUUID().toString(),
                    frozen.base(),
                    frozen.imageId(),
                    frozen.baseId(),
                    claim.vectorGenerationId(),
                    receipt.physicalSegmentId(),
                    imageTarget,
                    receipt.entrySha256(),
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
    var image =
        ingestion.findImageEvidence(base.sourceRevisionId()).orElseThrow(ModelValues::notFound);
    String baseId =
        RetrievalProjection.physicalSegmentId(base.projectionGenerationId(), image.id());
    var sources =
        evidence.findPublishedImageEvidence(
            new EvidenceScope(actor, selected, List.of(base)), List.of(baseId));
    if (sources.size() != 1
        || !sources.getFirst().publication().equals(base)
        || !sources.getFirst().image().equals(image)) {
      throw ModelValues.notFound();
    }
    var source = sources.getFirst();
    byte[] original = evidence.findImageOriginal(actor, base, ImageInput.MAX_BYTES);
    if (original == null || !ModelValues.sha256(original).equals(base.sourceSha256())) {
      throw ModelValues.notFound();
    }
    try {
      ImageInput.validateEnvelope(source.filename(), source.mediaType(), original);
      var dimensions = ImageInput.inspect(original);
      if (dimensions.width() != image.width() || dimensions.height() != image.height()) {
        throw ModelValues.notFound();
      }
    } catch (TextParser.Failure failure) {
      throw ModelValues.notFound();
    }
    return new Frozen(base, image.id(), baseId, new VisualImage(source.mediaType(), original));
  }

  private ImageVectorState state(Actor actor, Frozen frozen) {
    currentProfile();
    var saved = vectors.findPublications(actor.workspaceId(), List.of(frozen.base()), imageTarget);
    if (saved.size() > 1) {
      throw stale();
    }
    if (saved.isEmpty()) {
      return new ImageVectorState(frozen.base(), imageTarget, null);
    }
    var publication = saved.getFirst();
    String vectorId =
        RetrievalProjection.physicalSegmentId(publication.vectorGenerationId(), frozen.imageId());
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(),
            frozen.base().documentId(),
            publication.vectorGenerationId(),
            Map.of(vectorId, publication.entrySha256()));
    if (!publication.basePublication().equals(frozen.base())
        || !publication.imageEvidenceId().equals(frozen.imageId())
        || !publication.basePhysicalSegmentId().equals(frozen.baseId())
        || !publication.vectorPhysicalSegmentId().equals(vectorId)
        || !publication.target().equals(imageTarget)
        || !publication.manifestSha256().equals(manifest.sha256())) {
      throw stale();
    }
    return new ImageVectorState(frozen.base(), imageTarget, publication);
  }

  private static void verify(ImageVectorBuildClaim claim, ImageVectorReceipt receipt) {
    if (receipt == null) {
      throw stale();
    }
    try {
      String id =
          RetrievalProjection.physicalSegmentId(
              claim.vectorGenerationId(), claim.imageEvidenceId());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              claim.basePublication().sourceSha256(),
              receipt.vector());
      String digest = RetrievalProjection.entryDigest(entry);
      var manifest =
          new RetrievalProjection.RevisionManifest(
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              Map.of(id, digest));
      if (!id.equals(receipt.physicalSegmentId())
          || receipt.vector().size() != claim.target().dimensions()
          || !digest.equals(receipt.entrySha256())
          || !manifest.sha256().equals(receipt.verified().manifestSha256())
          || !claim.target().projectionIdentity().equals(receipt.verified().projectionIdentity())
          || receipt.verified().segmentCount() != 1) {
        throw stale();
      }
    } catch (RuntimeException failure) {
      throw stale();
    }
  }

  private void currentProfile() {
    if (!imageTarget.equals(profiles.get())) {
      throw stale();
    }
  }

  private void check(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw failure("image_vector_interrupted");
    }
    currentProfile();
    if (System.nanoTime() - started >= budget.toNanos()) {
      throw failure("image_vector_timeout");
    }
  }

  private Duration remaining(long started) {
    check(started);
    long nanos = budget.toNanos() - (System.nanoTime() - started);
    if (nanos < Duration.ofMillis(10).toNanos()) {
      throw failure("image_vector_timeout");
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
        && one.imageId().equals(two.imageId())
        && one.baseId().equals(two.baseId())
        && one.original().mediaType().equals(two.original().mediaType())
        && Arrays.equals(one.original().content(), two.original().content());
  }

  private static ApplicationException stale() {
    return new ApplicationException(
        FailureKind.CONFLICT, "image_vector_stale", "原图、发布版本或向量配置已变化，请重新建立原图向量。");
  }

  private static ApplicationException busy() {
    return new ApplicationException(
        FailureKind.CAPACITY_EXCEEDED, "image_vector_busy", "原图向量构建已达在途限额。");
  }

  private static ApplicationException failure(String code) {
    if ("image_vector_timeout".equals(code)) {
      return new ApplicationException(FailureKind.TIMEOUT, code, "原图向量构建超时。");
    }
    if ("image_vector_interrupted".equals(code) || "image_vector_closed".equals(code)) {
      return new ApplicationException(FailureKind.UNAVAILABLE, code, "原图向量构建已取消。");
    }
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "image_vector_unavailable", "原图向量构建未完成安全校验。");
  }

  private record Capture(Frozen frozen, ImageVectorState state) {}

  private record Frozen(
      PublicationVersion base, String imageId, String baseId, VisualImage original) {}
}
