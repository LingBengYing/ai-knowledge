package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvEvidence;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProof;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import java.util.List;

/** Field instruction policy and exact modality support for independently verified whole facts. */
public final class VideoAvProofBinding {
  public static final String POLICY_REVISION = VideoAvProofIdentity.POLICY_REVISION;

  private VideoAvProofBinding() {}

  public static boolean safeFacts(List<VideoAvFact> facts) {
    try {
      VideoAvProofIdentity.canonicalFactsJson(facts);
      for (var fact : facts) {
        var fields = SourceFields.split(fact.text());
        if (fields.isEmpty()
            || fields.stream()
                .anyMatch(field -> SourceInstructions.unsafe(fact.text(), field, fields))) {
          return false;
        }
      }
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public static boolean supported(VideoAvMode mode, List<VideoAvFact> facts) {
    return safeFacts(facts) && VideoAvProofIdentity.contributionsMatch(mode, facts);
  }

  public static VideoAvProof create(
      String questionSha,
      VideoAvEvidence evidence,
      VideoAvMode mode,
      List<VideoAvFact> facts,
      String modelRevision,
      String policyRevision) {
    if (!supported(mode, facts)) {
      throw ModelValues.invalid();
    }
    String factsSha = VideoAvProofIdentity.factsSha256(facts);
    var proof =
        new VideoAvProof(
            evidence,
            mode,
            facts,
            factsSha,
            VideoAvProofIdentity.digest(
                questionSha, evidence, mode, factsSha, modelRevision, policyRevision));
    if (!VideoAvProofIdentity.matches(proof, questionSha, modelRevision, policyRevision)) {
      throw ModelValues.invalid();
    }
    return proof;
  }
}
