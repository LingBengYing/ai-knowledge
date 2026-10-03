package com.evidence.rag.model.domain;

import java.util.List;

/** Terminal hash-only sound decision; a refused answer never carries citations. */
public record SoundTraceDraft(
    String questionSha256,
    String answerSha256,
    String status,
    String reasonCode,
    List<SoundProof> citations,
    String modelRevision,
    String policyRevision) {
  public SoundTraceDraft {
    SoundProfile.hash(questionSha256);
    ModelValues.identifier(modelRevision, 200);
    if (!"java-sound-answer-v1".equals(policyRevision)
        || citations == null
        || citations.size() > 32
        || citations.stream().anyMatch(item -> item == null)) {
      throw ModelValues.invalid();
    }
    if ("answered".equals(status)) {
      SoundProfile.hash(answerSha256);
      if (reasonCode != null || citations.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else if ("abstained".equals(status)) {
      ModelValues.indexIdentity(reasonCode);
      if (answerSha256 != null || !citations.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else {
      throw ModelValues.invalid();
    }
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "SoundTraceDraft[redacted]";
  }
}
