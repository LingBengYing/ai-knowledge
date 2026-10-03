package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAvQueryTraceDraftTest {
  private static final String QUESTION = VideoAvProofIdentity.sha("完整问题");
  private static final String POLICY = "java-video-av-answer-v1";

  @Test
  void originalEightArgumentConstructorIsIdenticalToExplicitNoQueryTrace() {
    var old =
        new VideoAvTraceDraft(
            QUESTION,
            null,
            "abstained",
            "empty_scope",
            VideoAvMode.JOINT,
            List.of(),
            "analysis-v1",
            POLICY);
    assertEquals(
        old,
        new VideoAvTraceDraft(
            QUESTION,
            null,
            "abstained",
            "empty_scope",
            VideoAvMode.JOINT,
            List.of(),
            "analysis-v1",
            POLICY,
            null));
    assertNull(old.queryTrace());
    assertEquals("VideoAvTraceDraft[redacted]", old.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"mode", "question"})
  void querySidecarMustBindExactParentQuestionAndMode(String wrong) {
    var claim = VideoAvTestFixture.claim(true);
    var attachment =
        VideoAvQueryManifest.notPrepared(
            0, VideoAvMode.JOINT, "compiler-v1", claim.original().sourceSha256());
    var query =
        new VideoAvQueryTrace(
            VideoAvMode.JOINT,
            QUESTION,
            claim.profileFingerprint(),
            claim.targets().visual().embeddingIdentity(),
            List.of(attachment));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvTraceDraft(
                wrong.equals("question") ? "0".repeat(64) : QUESTION,
                null,
                "abstained",
                "empty_scope",
                wrong.equals("mode") ? VideoAvMode.VISUAL : VideoAvMode.JOINT,
                List.of(),
                "analysis-v1",
                POLICY,
                query));
  }

  @Test
  void answeredTraceCannotSealAnUnpreparedReferenceGroupEvenWithValidLibraryProof() {
    var claim = VideoAvTestFixture.claim(true);
    var proof = proof(claim);
    var attachment =
        VideoAvQueryManifest.notPrepared(
            0, VideoAvMode.JOINT, "compiler-v1", claim.original().sourceSha256());
    var query =
        new VideoAvQueryTrace(
            VideoAvMode.JOINT,
            QUESTION,
            claim.profileFingerprint(),
            claim.targets().visual().embeddingIdentity(),
            List.of(attachment));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvTraceDraft(
                QUESTION,
                VideoAvProofIdentity.sha(proof.facts().getFirst().text()),
                "answered",
                null,
                VideoAvMode.JOINT,
                List.of(proof),
                "analysis-v1",
                POLICY,
                query));
  }

  @Test
  void answeredTraceAcceptsCompletePreparedIdentityWithoutMakingItProofEvidence() {
    var claim = VideoAvTestFixture.claim(true);
    var proof = proof(claim);
    var attachment =
        VideoAvQueryManifest.prepared(0, VideoAvMode.JOINT, "compiler-v1", claim.compilation());
    var query =
        new VideoAvQueryTrace(
            VideoAvMode.JOINT,
            QUESTION,
            claim.profileFingerprint(),
            claim.targets().visual().embeddingIdentity(),
            List.of(attachment));
    var trace =
        new VideoAvTraceDraft(
            QUESTION,
            VideoAvProofIdentity.sha(proof.facts().getFirst().text()),
            "answered",
            null,
            VideoAvMode.JOINT,
            List.of(proof),
            "analysis-v1",
            POLICY,
            query);
    assertEquals(List.of(proof), trace.citations());
    assertEquals(query, trace.queryTrace());
    assertEquals("VideoAvTraceDraft[redacted]", trace.toString());
  }

  private static VideoAvProof proof(VideoAvBuildClaim claim) {
    var publication = VideoAvTestFixture.publication(claim);
    var evidence = new VideoAvEvidence(publication, publication.windows().getFirst());
    var fact =
        new VideoAvFact(
            VideoAvProofIdentity.stableFactId(QUESTION, 0, "动作与响声同步。", VideoAvRequirement.JOINT),
            "动作与响声同步。",
            VideoAvRequirement.JOINT,
            true,
            true);
    String factsSha = VideoAvProofIdentity.factsSha256(List.of(fact));
    return new VideoAvProof(
        evidence,
        VideoAvMode.JOINT,
        List.of(fact),
        factsSha,
        VideoAvProofIdentity.digest(
            QUESTION, evidence, VideoAvMode.JOINT, factsSha, "analysis-v1", POLICY));
  }
}
