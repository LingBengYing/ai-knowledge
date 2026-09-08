package com.evidence.rag.model.domain;

import java.util.List;
import java.util.Set;

/** Hash-only trace input. Answer text must remain outside the authority persistence boundary. */
public record TraceDraft(
    String questionSha256,
    String answerSha256,
    String outcome,
    String reasonCode,
    String modelRevision,
    String promptRevision,
    String policyRevision,
    List<TraceEvidence> evidence) {
  public TraceDraft {
    ModelValues.identifier(modelRevision, 160);
    ModelValues.identifier(promptRevision, 160);
    ModelValues.identifier(policyRevision, 160);
    if (questionSha256 == null
        || !questionSha256.matches("[a-f0-9]{64}")
        || outcome == null
        || !Set.of("answered", "abstained").contains(outcome)
        || evidence == null
        || evidence.size() > 32) {
      throw ModelValues.invalid();
    }
    if ("answered".equals(outcome)) {
      if (answerSha256 == null
          || !answerSha256.matches("[a-f0-9]{64}")
          || reasonCode != null
          || evidence.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else if (answerSha256 != null
        || !evidence.isEmpty()
        || reasonCode == null
        || !reasonCode.matches("[a-z][a-z0-9_]{0,63}")) {
      throw ModelValues.invalid();
    }
    for (int index = 0; index < evidence.size(); index++) {
      if (evidence.get(index) == null || evidence.get(index).citationOrdinal() != index + 1) {
        throw ModelValues.invalid();
      }
    }
    evidence = List.copyOf(evidence);
  }

  @Override
  public String toString() {
    return "TraceDraft[redacted]";
  }
}
