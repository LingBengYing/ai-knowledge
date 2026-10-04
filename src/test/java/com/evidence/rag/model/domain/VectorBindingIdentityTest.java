package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VectorBindingIdentityTest {
  static final String WORKSPACE = "vector-binding-fixture";
  static final String GENERATION = "00000000-0000-0000-0000-000000000001";
  static final String VECTOR_GENERATION = "00000000-0000-0000-0000-000000000002";
  static final IndexTarget TEXT = new IndexTarget("text-v1", "b".repeat(64), "text-model-v1", 2);
  static final IndexTarget MEDIA = new IndexTarget("media-v1", "c".repeat(64), "media-model-v1", 2);

  static PublicationVersion base(String id, String generation, int count) {
    return new PublicationVersion(
        "doc",
        id,
        "revision",
        generation,
        "a".repeat(64),
        "parser-v1",
        TEXT,
        "d".repeat(64),
        count);
  }

  static ImageVectorPublication image(PublicationVersion base, String id, IndexTarget target) {
    String evidence = "image-fixture";
    String physical = VectorBindingIdentity.physicalSegmentId(VECTOR_GENERATION, evidence);
    String entry = "e".repeat(64);
    return new ImageVectorPublication(
        id,
        base,
        evidence,
        VectorBindingIdentity.physicalSegmentId(base.projectionGenerationId(), evidence),
        VECTOR_GENERATION,
        physical,
        target,
        entry,
        VectorBindingIdentity.manifestSha256(
            WORKSPACE, base.documentId(), VECTOR_GENERATION, Map.of(physical, entry)),
        "2026-10-03T00:00:00Z");
  }

  static AudioVectorPublication audio(PublicationVersion base) {
    var entries = new ArrayList<AudioVectorEntry>();
    for (int ordinal : List.of(0, 2)) {
      String evidence =
          "audio-"
              + ModelValues.sha256(
                  (base.sourceRevisionId() + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
      entries.add(
          new AudioVectorEntry(
              evidence,
              VectorBindingIdentity.physicalSegmentId(base.projectionGenerationId(), evidence),
              VectorBindingIdentity.physicalSegmentId(VECTOR_GENERATION, evidence),
              ordinal,
              ordinal * 16000L,
              (ordinal + 1) * 16000L,
              "f".repeat(64),
              "e".repeat(64)));
    }
    var digests =
        Map.of(
            entries.getFirst().vectorPhysicalSegmentId(),
            entries.getFirst().entrySha256(),
            entries.getLast().vectorPhysicalSegmentId(),
            entries.getLast().entrySha256());
    return new AudioVectorPublication(
        "audio-origin",
        base,
        MEDIA,
        VECTOR_GENERATION,
        "decoder-v1",
        entries,
        VectorBindingIdentity.manifestSha256(
            WORKSPACE, base.documentId(), VECTOR_GENERATION, digests),
        "2026-10-03T00:00:00Z");
  }

  @Test
  void physicalAndRemoteManifestAreByteIdenticalToTheExistingProjectionProtocol() {
    var base = base("base", GENERATION, 1);
    var origin = image(base, "origin", MEDIA);
    assertEquals(
        RetrievalProjection.physicalSegmentId(VECTOR_GENERATION, origin.imageEvidenceId()),
        origin.vectorPhysicalSegmentId());
    assertEquals(
        new RetrievalProjection.RevisionManifest(
                WORKSPACE,
                "doc",
                VECTOR_GENERATION,
                Map.of(origin.vectorPhysicalSegmentId(), origin.entrySha256()))
            .sha256(),
        origin.manifestSha256());
    assertEquals(origin, VectorBindingIdentity.directImage(origin).origin());
    assertThrows(ApplicationException.class, () -> VectorBindingIdentity.directImage(null));
    assertThrows(ApplicationException.class, () -> VectorBindingIdentity.directAudio(null));
  }

  @Test
  void successiveMappingsRetainTheOriginalAssetAndBindTheDirectPreviousPublication() {
    var original = base("base", GENERATION, 1);
    var origin = image(original, "origin", MEDIA);
    var current = base("current", "00000000-0000-0000-0000-000000000003", 1);
    String mapped =
        VectorBindingIdentity.physicalSegmentId(
            current.projectionGenerationId(), origin.imageEvidenceId());
    var inherited =
        new ImageVectorBinding(
            current,
            origin,
            mapped,
            original.publicationId(),
            VectorBindingIdentity.imageSha256(current, origin, mapped, original.publicationId()));
    assertEquals(origin, inherited.origin());
    assertNotEquals(origin.basePhysicalSegmentId(), inherited.currentBasePhysicalSegmentId());
    assertNotEquals(
        VectorBindingIdentity.directImage(origin).bindingSha256(), inherited.bindingSha256());
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBinding(
                current,
                origin,
                mapped,
                null,
                VectorBindingIdentity.imageSha256(current, origin, mapped, null)));
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBinding(
                current,
                origin,
                mapped,
                current.publicationId(),
                VectorBindingIdentity.imageSha256(
                    current, origin, mapped, current.publicationId())));
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBinding(
                current, origin, origin.basePhysicalSegmentId(), "base", "a".repeat(64)));
    assertThrows(
        ApplicationException.class,
        () -> new ImageVectorBinding(current, origin, mapped, "base", "a".repeat(64)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"document", "revision", "source", "parser", "target", "count"})
  void anInheritanceCannotClaimAnotherSourceOrTextProfile(String changed) {
    var base = base("base", GENERATION, 1);
    var origin = image(base, "origin", MEDIA);
    var current =
        new PublicationVersion(
            changed.equals("document") ? "other" : base.documentId(),
            "current",
            changed.equals("revision") ? "other" : base.sourceRevisionId(),
            "00000000-0000-0000-0000-000000000003",
            changed.equals("source") ? "f".repeat(64) : base.sourceSha256(),
            changed.equals("parser") ? "other" : base.parserRevision(),
            changed.equals("target") ? MEDIA : TEXT,
            base.manifestSha256(),
            changed.equals("count") ? 2 : 1);
    String mapped =
        VectorBindingIdentity.physicalSegmentId(
            current.projectionGenerationId(), origin.imageEvidenceId());
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBinding(
                current,
                origin,
                mapped,
                "base",
                VectorBindingIdentity.imageSha256(current, origin, mapped, "base")));
  }

  @Test
  void audioKeepsAllSpeechOrdinalsIncludingTheSilentGapAndDefensiveMappingCopies() {
    var base = base("base", GENERATION, 2);
    var origin = audio(base);
    var direct = VectorBindingIdentity.directAudio(origin);
    assertEquals(
        List.of(0, 2), direct.origin().entries().stream().map(AudioVectorEntry::ordinal).toList());
    var mapping = new ArrayList<>(direct.currentBasePhysicalSegmentIds());
    var value = new AudioVectorBinding(base, origin, mapping, null, direct.bindingSha256());
    mapping.clear();
    assertEquals(2, value.currentBasePhysicalSegmentIds().size());
    assertThrows(
        UnsupportedOperationException.class, () -> value.currentBasePhysicalSegmentIds().clear());
    assertThrows(
        ApplicationException.class,
        () -> new AudioVectorBinding(base, origin, List.of(), null, direct.bindingSha256()));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBinding(
                base,
                origin,
                List.of(
                    direct.currentBasePhysicalSegmentIds().getFirst(),
                    direct.currentBasePhysicalSegmentIds().getFirst()),
                null,
                direct.bindingSha256()));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBinding(
                base,
                origin,
                List.of(
                    direct.currentBasePhysicalSegmentIds().getLast(),
                    direct.currentBasePhysicalSegmentIds().getFirst()),
                null,
                direct.bindingSha256()));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBinding(
                base, origin, direct.currentBasePhysicalSegmentIds(), null, "a".repeat(64)));
  }

  @Test
  void setIdentityIsOrderIndependentButDoesNotIgnoreAnyProfileOrWorkspace() {
    var base = base("base", GENERATION, 1);
    var one = VectorBindingIdentity.directImage(image(base, "one", MEDIA));
    var otherTarget = new IndexTarget("media-v2", "d".repeat(64), "media-model-v2", 2);
    var two = VectorBindingIdentity.directImage(image(base, "two", otherTarget));
    String sha = VectorBindingIdentity.setSha256(WORKSPACE, base, List.of(one, two), List.of());
    var plan = new ReindexVectorPlan("job", WORKSPACE, base, sha, List.of(two, one), List.of());
    assertFalse(plan.isEmpty());
    assertNotEquals(
        sha, VectorBindingIdentity.setSha256("another", base, plan.images(), List.of()));
    assertNotEquals(sha, VectorBindingIdentity.setSha256(WORKSPACE, base, List.of(one), List.of()));
    assertThrows(
        ApplicationException.class,
        () -> new ReindexVectorPlan("job", WORKSPACE, base, sha, List.of(one, one), List.of()));
    var duplicateProfile = VectorBindingIdentity.directImage(image(base, "another-origin", MEDIA));
    assertThrows(
        ApplicationException.class,
        () ->
            new ReindexVectorPlan(
                "job",
                WORKSPACE,
                base,
                VectorBindingIdentity.setSha256(
                    WORKSPACE, base, List.of(one, duplicateProfile), List.of()),
                List.of(one, duplicateProfile),
                List.of()));
    assertThrows(
        ApplicationException.class,
        () ->
            new ReindexVectorPlan(
                "job", WORKSPACE, base, "f".repeat(64), List.of(one, two), List.of()));
    assertTrue(
        new ReindexVectorPlan(
                "empty",
                WORKSPACE,
                base,
                VectorBindingIdentity.setSha256(WORKSPACE, base, List.of(), List.of()),
                List.of(),
                List.of())
            .isEmpty());
  }

  @Test
  void verifiedResultsMustCoverEveryOriginWithItsActualManifestTargetAndCount() {
    var base = base("base", GENERATION, 1);
    var binding = VectorBindingIdentity.directImage(image(base, "origin", MEDIA));
    var plan =
        new ReindexVectorPlan(
            "job",
            WORKSPACE,
            base,
            VectorBindingIdentity.setSha256(WORKSPACE, base, List.of(binding), List.of()),
            List.of(binding),
            List.of());
    var good =
        new ReindexVectorVerification(
            "image",
            "origin",
            MEDIA,
            new VerifiedRevision(MEDIA.projectionIdentity(), binding.origin().manifestSha256(), 1));
    assertEquals(List.of(good), new VerifiedReindexVectors(plan, List.of(good)).receipts());
    assertThrows(ApplicationException.class, () -> new VerifiedReindexVectors(plan, List.of()));
    assertThrows(
        ApplicationException.class, () -> new VerifiedReindexVectors(plan, List.of(good, good)));
    for (var bad :
        List.of(
            new ReindexVectorVerification("audio", "origin", MEDIA, good.verified()),
            new ReindexVectorVerification("image", "unknown", MEDIA, good.verified()),
            new ReindexVectorVerification(
                "image",
                "origin",
                MEDIA,
                new VerifiedRevision(MEDIA.projectionIdentity(), "a".repeat(64), 1)),
            new ReindexVectorVerification(
                "image",
                "origin",
                MEDIA,
                new VerifiedRevision(
                    MEDIA.projectionIdentity(), binding.origin().manifestSha256(), 2)))) {
      assertThrows(
          ApplicationException.class, () -> new VerifiedReindexVectors(plan, List.of(bad)));
    }
    assertThrows(
        ApplicationException.class,
        () -> new ReindexVectorVerification("video", "origin", MEDIA, good.verified()));
    assertThrows(
        ApplicationException.class,
        () -> new ReindexVectorVerification("image", "origin", TEXT, good.verified()));
  }
}
