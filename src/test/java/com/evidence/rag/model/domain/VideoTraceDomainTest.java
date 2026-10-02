package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.entity.TraceVideoCitationEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoTraceDomainTest {
  private static final String FIRST = "a".repeat(64);
  private static final String LAST = "b".repeat(64);
  private static final String GROUP = "video-group-" + "c".repeat(64);

  @Test
  void factAuditAcceptsOnlyBoundIdentitiesAndBinaryContributions() {
    assertEquals(1, new VideoTraceFact(7, FIRST, 1, 1).transcriptSupport());
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(-1, FIRST, 1, 0));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, null, 1, 0));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, "claim-text", 1, 0));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, FIRST, -1, 1));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, FIRST, 1, -1));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, FIRST, 1, 2));
  }

  @Test
  void citationRequiresFiniteRanksBoundFactAndARealHalfOpenTranscriptRange() {
    assertEquals(
        1200,
        new VideoTraceEvidence(
                    32, VideoTraceEvidence.Kind.TRANSCRIPT, "physical", 5, 1205, -2, 10, FIRST)
                .endCodePoint()
            - 5);
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                33, VideoTraceEvidence.Kind.VISUAL, "physical", null, null, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () -> new VideoTraceEvidence(1, null, "physical", null, null, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1,
                VideoTraceEvidence.Kind.VISUAL,
                "physical",
                null,
                null,
                1,
                Double.POSITIVE_INFINITY,
                FIRST));
    for (String fact : Arrays.asList(null, "unbound-fact")) {
      assertThrows(
          ApplicationException.class,
          () ->
              new VideoTraceEvidence(
                  1, VideoTraceEvidence.Kind.VISUAL, "physical", null, null, 1, 1, fact));
    }
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.VISUAL, "physical", null, 3, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical", 0, null, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical", -1, 3, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical", 3, 3, 1, 1, FIRST));
  }

  @Test
  void proofRequiresARealGroupAndOneToEightCompleteModeSpecificFacts() {
    var both = new VideoTraceFact(0, FIRST, 1, 1);
    for (String group : Arrays.asList(null, "scene-one")) {
      assertThrows(
          ApplicationException.class,
          () ->
              new VideoTraceProof(
                  "publication",
                  group,
                  VideoAssessment.Mode.JOINT,
                  List.of(both),
                  "text",
                  "vision",
                  "policy"));
    }
    assertThrows(ApplicationException.class, () -> proof(null, List.of(both)));
    assertThrows(ApplicationException.class, () -> proof(VideoAssessment.Mode.JOINT, null));
    assertThrows(ApplicationException.class, () -> proof(VideoAssessment.Mode.JOINT, List.of()));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.JOINT, Arrays.asList((VideoTraceFact) null)));
    var eight = new ArrayList<VideoTraceFact>();
    for (int ordinal = 0; ordinal < 8; ordinal++) {
      eight.add(new VideoTraceFact(ordinal, Integer.toString(ordinal).repeat(64), 1, 1));
    }
    assertEquals(8, proof(VideoAssessment.Mode.JOINT, eight).facts().size());
    eight.add(both);
    assertThrows(ApplicationException.class, () -> proof(VideoAssessment.Mode.JOINT, eight));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.JOINT, List.of(new VideoTraceFact(0, FIRST, 0, 1))));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.VISUAL, List.of(new VideoTraceFact(0, FIRST, 0, 1))));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.TRANSCRIPT, List.of(new VideoTraceFact(0, FIRST, 1, 0))));
    assertEquals(
        1,
        proof(VideoAssessment.Mode.TRANSCRIPT, List.of(new VideoTraceFact(0, FIRST, 0, 1)))
            .facts()
            .size());
  }

  @Test
  void traceDoesNotSubstituteUnplannedFactsOrInventAnotherModalityContribution() {
    var visualProof =
        proof(VideoAssessment.Mode.VISUAL, List.of(new VideoTraceFact(0, FIRST, 1, 0)));
    assertThrows(
        ApplicationException.class,
        () ->
            draft(
                List.of(),
                List.of(evidence(1, VideoTraceEvidence.Kind.VISUAL, LAST)),
                visualProof));
    assertThrows(
        ApplicationException.class,
        () ->
            draft(
                List.of(),
                List.of(
                    evidence(1, VideoTraceEvidence.Kind.VISUAL, FIRST),
                    evidence(2, VideoTraceEvidence.Kind.TRANSCRIPT, FIRST)),
                visualProof));
    assertThrows(
        ApplicationException.class,
        () -> draft(List.of(), Arrays.asList((VideoTraceEvidence) null), visualProof));
    assertThrows(
        ApplicationException.class,
        () ->
            draft(
                List.of(),
                List.of(
                    evidence(2, VideoTraceEvidence.Kind.VISUAL, FIRST),
                    evidence(1, VideoTraceEvidence.Kind.VISUAL, FIRST)),
                visualProof));
    var mutable = new ArrayList<>(List.of(evidence(1, VideoTraceEvidence.Kind.VISUAL, FIRST)));
    var trace = draft(List.of(), mutable, visualProof);
    mutable.clear();
    assertEquals(1, trace.videoEvidence().size());
    assertThrows(UnsupportedOperationException.class, () -> trace.videoEvidence().clear());
  }

  @Test
  void distinguishesRealTranscriptRangesFromVisualReferences() {
    var visual = evidence(1, VideoTraceEvidence.Kind.VISUAL, FIRST);
    var transcript = evidence(2, VideoTraceEvidence.Kind.TRANSCRIPT, LAST);
    assertEquals(null, visual.startCodePoint());
    assertEquals(3, transcript.endCodePoint());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.VISUAL, "physical", 0, 3, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical", null, null, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical", 0, 1201, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                0, VideoTraceEvidence.Kind.VISUAL, "physical", null, null, 1, 1, FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTraceEvidence(
                1, VideoTraceEvidence.Kind.VISUAL, "physical", null, null, Double.NaN, 1, FIRST));
  }

  @Test
  void freezesDenseFactCoverageAndRejectsModeOrSupportMismatches() {
    var facts =
        new ArrayList<>(
            List.of(new VideoTraceFact(0, FIRST, 1, 0), new VideoTraceFact(1, LAST, 0, 1)));
    var proof = proof(VideoAssessment.Mode.JOINT, facts);
    facts.clear();
    assertEquals(2, proof.facts().size());
    assertThrows(UnsupportedOperationException.class, () -> proof.facts().clear());
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, FIRST, 0, 0));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(0, FIRST, 2, 0));
    assertThrows(ApplicationException.class, () -> new VideoTraceFact(8, FIRST, 1, 0));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.JOINT, List.of(new VideoTraceFact(0, FIRST, 1, 0))));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.VISUAL, List.of(new VideoTraceFact(0, FIRST, 1, 1))));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.TRANSCRIPT, List.of(new VideoTraceFact(0, FIRST, 1, 1))));
    assertThrows(
        ApplicationException.class,
        () -> proof(VideoAssessment.Mode.JOINT, List.of(new VideoTraceFact(1, FIRST, 1, 1))));
    assertThrows(
        ApplicationException.class,
        () ->
            proof(
                VideoAssessment.Mode.JOINT,
                List.of(new VideoTraceFact(0, FIRST, 1, 0), new VideoTraceFact(1, FIRST, 0, 1))));
  }

  @Test
  void traceRequiresExactFactToCitationKindsAndRetainsGlobalOrdinalsAcrossModalities() {
    var proof =
        proof(
            VideoAssessment.Mode.JOINT,
            List.of(new VideoTraceFact(0, FIRST, 1, 0), new VideoTraceFact(1, LAST, 0, 1)));
    var visual = evidence(1, VideoTraceEvidence.Kind.VISUAL, FIRST);
    var transcript = evidence(2, VideoTraceEvidence.Kind.TRANSCRIPT, LAST);
    var trace = draft(List.of(), List.of(visual, transcript), proof);
    assertEquals(proof, trace.videoProof());
    assertThrows(ApplicationException.class, () -> draft(List.of(), List.of(visual), proof));
    assertThrows(
        ApplicationException.class,
        () ->
            draft(
                List.of(),
                List.of(evidence(1, VideoTraceEvidence.Kind.TRANSCRIPT, FIRST), transcript),
                proof));
    assertThrows(
        ApplicationException.class, () -> draft(List.of(), List.of(visual, transcript), null));
    assertThrows(ApplicationException.class, () -> draft(List.of(), List.of(), proof));
    var text = new TraceEvidence(1, "physical-text", 0, 1, 1, 1, List.of(FIRST));
    assertEquals(
        2,
        draft(
                List.of(text),
                List.of(
                    evidence(2, VideoTraceEvidence.Kind.VISUAL, FIRST),
                    evidence(3, VideoTraceEvidence.Kind.TRANSCRIPT, LAST)),
                proof)
            .videoEvidence()
            .size());
    assertThrows(
        ApplicationException.class, () -> draft(List.of(text), List.of(visual, transcript), proof));
    assertThrows(
        ApplicationException.class,
        () ->
            draft(
                List.of(),
                List.of(
                    evidence(2, VideoTraceEvidence.Kind.VISUAL, FIRST),
                    evidence(3, VideoTraceEvidence.Kind.TRANSCRIPT, LAST)),
                proof));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceDraft(
                FIRST,
                null,
                "abstained",
                "scope_changed",
                "model",
                "prompt",
                "policy",
                List.of(),
                List.of(),
                List.of(),
                List.of(visual, transcript),
                proof));
  }

  @Test
  void keepsGlobalThirtyTwoCitationLimitAndOldTraceConstructor() {
    var evidence = new ArrayList<VideoTraceEvidence>();
    for (int ordinal = 1; ordinal <= 32; ordinal++) {
      evidence.add(evidence(ordinal, VideoTraceEvidence.Kind.VISUAL, FIRST));
    }
    var proof = proof(VideoAssessment.Mode.VISUAL, List.of(new VideoTraceFact(0, FIRST, 1, 0)));
    assertEquals(32, draft(List.of(), evidence, proof).videoEvidence().size());
    var text = new TraceEvidence(1, "physical-text", 0, 1, 1, 1, List.of(FIRST));
    assertThrows(ApplicationException.class, () -> draft(List.of(text), evidence, proof));
    var old =
        new TraceDraft(FIRST, LAST, "answered", null, "model", "prompt", "policy", List.of(text));
    assertEquals(List.of(), old.videoEvidence());
    assertEquals(null, old.videoProof());
  }

  @Test
  void citationEntityStoresOnlyTheReferencedModalityHashes() {
    var visual = evidence(1, VideoTraceEvidence.Kind.VISUAL, FIRST);
    var transcript = evidence(2, VideoTraceEvidence.Kind.TRANSCRIPT, LAST);
    var image =
        new TraceVideoCitationEntity(
            visual,
            "publication",
            GROUP,
            "video-frame-" + FIRST,
            null,
            FIRST,
            LAST,
            FIRST,
            null,
            null);
    assertEquals(null, image.quoteSha256());
    var audio =
        new TraceVideoCitationEntity(
            transcript,
            "publication",
            GROUP,
            null,
            "video-transcript-" + FIRST,
            FIRST,
            LAST,
            null,
            FIRST,
            LAST);
    assertEquals(LAST, audio.quoteSha256());
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoCitationEntity(
                visual,
                "publication",
                GROUP,
                "video-frame-" + FIRST,
                null,
                FIRST,
                LAST,
                FIRST,
                null,
                FIRST));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoCitationEntity(
                transcript,
                "publication",
                GROUP,
                null,
                "video-transcript-" + FIRST,
                FIRST,
                LAST,
                null,
                FIRST,
                null));
  }

  private static VideoTraceEvidence evidence(
      int ordinal, VideoTraceEvidence.Kind kind, String fact) {
    return new VideoTraceEvidence(
        ordinal,
        kind,
        "physical-" + kind.name().toLowerCase(java.util.Locale.ROOT),
        kind == VideoTraceEvidence.Kind.VISUAL ? null : 0,
        kind == VideoTraceEvidence.Kind.VISUAL ? null : 3,
        0.8,
        0.9,
        fact);
  }

  private static VideoTraceProof proof(VideoAssessment.Mode mode, List<VideoTraceFact> facts) {
    return new VideoTraceProof(
        "publication", GROUP, mode, facts, "text-model", "vision-model", "policy");
  }

  private static TraceDraft draft(
      List<TraceEvidence> text, List<VideoTraceEvidence> video, VideoTraceProof proof) {
    return new TraceDraft(
        FIRST,
        LAST,
        "answered",
        null,
        "model",
        "prompt",
        "policy",
        text,
        List.of(),
        List.of(),
        video,
        proof);
  }
}
