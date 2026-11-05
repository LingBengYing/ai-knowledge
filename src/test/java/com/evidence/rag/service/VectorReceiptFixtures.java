package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Saved image/audio vector receipts and image publications for reindex authority tests. */
final class VectorReceiptFixtures {
  static final String AUDIO_DECODER = "test-audio-decoder-v1";
  static final String AUDIO_FACT = "星港项目的预算为47万元。";
  static final String AUDIO_QUESTION = "星港项目的预算是多少？";
  static final IndexTarget AUDIO_TARGET =
      new IndexTarget("audio-profile-v1", "d".repeat(64), "audio-profile-v1", 2);
  static final IndexTarget IMAGE_TARGET =
      new IndexTarget("image-profile-v1", "e".repeat(64), "image-profile-v1", 2);

  private VectorReceiptFixtures() {}

  static AudioVectorPublication publishAudioVectors(
      AnswerTestContext context, EvidenceScope scope, AudioTestFixture.Published published) {
    var store = context.authority.store();
    var found =
        store.transaction(
            () ->
                new EvidenceRepository(store)
                    .findPublishedAudioEvidence(scope, published.physicalIds()));
    var sources =
        published.physicalIds().stream()
            .map(
                id ->
                    found.stream()
                        .filter(item -> item.physicalSegmentId().equals(id))
                        .findFirst()
                        .orElseThrow())
            .toList();
    var base = sources.getFirst().publication();
    String generation = UUID.randomUUID().toString();
    var entries = new ArrayList<AudioVectorEntry>();
    var digests = new TreeMap<String, String>();
    for (var source : sources) {
      var span = source.span();
      String pcmSha = ModelValues.sha256(new byte[(int) ((span.endMs() - span.startMs()) * 32)]);
      String vectorId = RetrievalProjection.physicalSegmentId(generation, span.id());
      String digest =
          RetrievalProjection.entryDigest(
              new RetrievalProjection.Entry(
                  vectorId,
                  context.owner.workspaceId(),
                  base.documentId(),
                  generation,
                  pcmSha,
                  List.of(1.0, 0.0)));
      entries.add(
          new AudioVectorEntry(
              span.id(),
              source.physicalSegmentId(),
              vectorId,
              span.ordinal(),
              span.startMs() * 16,
              span.endMs() * 16,
              pcmSha,
              digest));
      digests.put(vectorId, digest);
    }
    var result =
        new AudioVectorPublication(
            UUID.randomUUID().toString(),
            base,
            AUDIO_TARGET,
            generation,
            AUDIO_DECODER,
            entries,
            new RetrievalProjection.RevisionManifest(
                    context.owner.workspaceId(), base.documentId(), generation, digests)
                .sha256(),
            Instant.now().toString());
    store.transaction(
        () -> {
          new AudioVectorRepository(store).insert(result);
          return null;
        });
    return result;
  }

  static ImageVectorPublication publishImageVector(
      AnswerTestContext context, EvidenceScope scope, String documentId) {
    var store = context.authority.store();
    var publication =
        scope.publications().stream()
            .filter(item -> item.documentId().equals(documentId))
            .findFirst()
            .orElseThrow();
    var physicalIds =
        context
            .projection
            .data
            .search(
                new RetrievalProjection.Query(
                    "fixture",
                    List.of(1.0, 0.0),
                    new RetrievalProjection.AuthorizedScope(
                        context.owner.workspaceId(),
                        Map.of(documentId, publication.projectionGenerationId())),
                    64))
            .stream()
            .map(RetrievalProjection.Candidate::segmentId)
            .toList();
    var image =
        store
            .transaction(
                () -> new EvidenceRepository(store).findPublishedImageEvidence(scope, physicalIds))
            .getFirst();
    String generation = UUID.randomUUID().toString();
    String vectorId = RetrievalProjection.physicalSegmentId(generation, image.image().id());
    var entry =
        new RetrievalProjection.Entry(
            vectorId,
            context.owner.workspaceId(),
            documentId,
            generation,
            publication.sourceSha256(),
            List.of(1.0, 0.0));
    String digest = RetrievalProjection.entryDigest(entry);
    var manifest =
        new RetrievalProjection.RevisionManifest(
            context.owner.workspaceId(), documentId, generation, Map.of(vectorId, digest));
    var saved =
        new ImageVectorPublication(
            UUID.randomUUID().toString(),
            publication,
            image.image().id(),
            image.physicalSegmentId(),
            generation,
            vectorId,
            IMAGE_TARGET,
            digest,
            manifest.sha256(),
            Instant.now().toString());
    store.transaction(
        () -> {
          new ImageVectorRepository(store).insert(saved);
          return null;
        });
    return saved;
  }

  static String publishImage(AnswerTestContext context, String visionRevision) {
    var store = context.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions(visionRevision));
    VisualImage image;
    try {
      image = VisualSyntheticFixture.image("png");
    } catch (IOException failure) {
      throw new AssertionError("Synthetic image encoding failed", failure);
    }
    ingestion.uploadDocument(context.owner, "library.png", image.mediaType(), image.content());
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeVisualIngestion(
            claim, new ImageRecall("Library recall only, not query evidence.", visionRevision)));
    context.authority.createIndexing(context.owner, claim.documentId(), context.target);
    var indexing = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    var entries =
        indexing.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            indexing.projectionGenerationId(), item.evidenceId()),
                        context.owner.workspaceId(),
                        indexing.documentId(),
                        indexing.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    context.projection.data.initialize();
    context.projection.data.upsert(entries);
    var hashes = new TreeMap<String, String>();
    entries.forEach(entry -> hashes.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    assertTrue(
        context.authority.completeIndexing(
            indexing,
            hashes,
            context.projection.data.verify(
                new RetrievalProjection.RevisionManifest(
                    context.owner.workspaceId(),
                    indexing.documentId(),
                    indexing.projectionGenerationId(),
                    hashes))));
    return claim.documentId();
  }
}
