package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundProof;
import com.evidence.rag.model.domain.SoundProofIdentity;
import com.evidence.rag.model.domain.SoundPublishedSpan;
import java.util.List;

/** Fact instruction policy and proof creation after complete waveform verification. */
public final class SoundProofBinding {
  public static final String POLICY_REVISION = SoundProofIdentity.POLICY_REVISION;

  private SoundProofBinding() {}

  public static boolean safeFacts(List<String> facts) {
    try {
      SoundProof.factsJson(facts);
      for (String fact : facts) {
        var fields = SourceFields.split(fact);
        if (fields.isEmpty()
            || fields.stream().anyMatch(field -> SourceInstructions.unsafe(fact, field, fields))) {
          return false;
        }
      }
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public static SoundProof create(
      String questionSha,
      SoundPublishedSpan source,
      List<String> facts,
      String modelRevision,
      String policyRevision) {
    if (!safeFacts(facts)) {
      throw ModelValues.invalid();
    }
    String factsSha = SoundProof.factsSha256(facts);
    return new SoundProof(
        source,
        facts,
        factsSha,
        SoundProofIdentity.digest(questionSha, source, factsSha, modelRevision, policyRevision));
  }

  public static boolean matches(
      SoundProof proof, String questionSha, String modelRevision, String policyRevision) {
    return safeFacts(proof.facts())
        && SoundProofIdentity.matches(proof, questionSha, modelRevision, policyRevision);
  }

  public static String sha(String value) {
    return SoundProofIdentity.sha(value);
  }
}
