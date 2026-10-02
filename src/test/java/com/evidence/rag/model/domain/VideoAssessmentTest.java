package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoCompilationFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAssessmentTest {
  private static final String FACT = "f".repeat(64);

  @Test
  void rejectsTranscriptProofFromAnotherSpanEvenWhenFactIdentityMatches() {
    var evidence =
        VideoEvidence.fromCompilation("video-revision", VideoCompilationFixture.compilation(true));
    var group = evidence.groups().getFirst();
    var foreign = evidence.spans().getLast().id();
    var proof =
        new VideoFactProof(
            FACT, List.of(), List.of(new GroundedQuote(foreign, 0, 2, "事实", List.of(FACT))));
    assertThrows(
        ApplicationException.class,
        () -> result(group, List.of(proof), VideoAssessment.Mode.TRANSCRIPT, null));
  }

  @Test
  void requiresCompletePlanAndBothModalitiesForJointSuccess() {
    var group =
        VideoEvidence.fromCompilation("video-revision", VideoCompilationFixture.compilation(true))
            .groups()
            .getFirst();
    var visual = new VideoFactProof(FACT, List.of("蓝色。"), List.of());
    assertThrows(
        ApplicationException.class,
        () -> result(group, List.of(visual), VideoAssessment.Mode.JOINT, null));
    assertThrows(
        ApplicationException.class,
        () -> result(group, List.of(), VideoAssessment.Mode.VISUAL, null));
    assertThrows(
        ApplicationException.class,
        () -> result(group, List.of(visual), VideoAssessment.Mode.VISUAL, "incomplete_evidence"));
    assertTrue(result(group, List.of(visual), VideoAssessment.Mode.VISUAL, null).supported());
  }

  @Test
  void proofHasDiscreteSupportAndDefensiveCopiesWithoutQuestionFragments() {
    var claims = new ArrayList<>(List.of("蓝色。"));
    var proof = new VideoFactProof(FACT, claims, List.of());
    claims.clear();
    assertEquals(1, proof.visualSupport());
    assertEquals(0, proof.transcriptSupport());
    assertEquals("VideoFactProof[redacted]", proof.toString());
    assertThrows(ApplicationException.class, () -> new VideoFactProof(FACT, List.of(), List.of()));
    assertThrows(
        ApplicationException.class,
        () -> new VideoFactProof(FACT, List.of("蓝色。", "蓝色。"), List.of()));
    assertThrows(
        ApplicationException.class, () -> new VideoFactProof(FACT, List.of("蓝\u0000色"), List.of()));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoFactProof(
                FACT,
                List.of(),
                List.of(new GroundedQuote("other", 0, 2, "事实", List.of("a".repeat(64))))));
  }

  private static VideoAssessment result(
      VideoEvidenceGroup group,
      List<VideoFactProof> proofs,
      VideoAssessment.Mode mode,
      String reason) {
    return new VideoAssessment(
        "a".repeat(64),
        "b".repeat(64),
        group,
        "c".repeat(64),
        mode,
        List.of(FACT),
        proofs,
        "text-v1",
        "vision-v1",
        "policy-v1",
        reason);
  }
}
