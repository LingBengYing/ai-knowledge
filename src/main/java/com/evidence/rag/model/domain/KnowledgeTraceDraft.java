package com.evidence.rag.model.domain;

import java.util.List;

/** Hash-only answer identity and original references; source bodies are never stored here. */
public record KnowledgeTraceDraft(
    String questionSha256, String answerSha256, String status, String reason,
    String modelRevision, String promptRevision, String policyRevision,
    List<KnowledgeReference> references) {
  public KnowledgeTraceDraft {
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(promptRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    if (questionSha256 == null || !questionSha256.matches("[a-f0-9]{64}")
        || references == null || references.size() > 32
        || !("answered".equals(status) || "abstained".equals(status))) {
      throw ModelValues.invalid();
    }
    if ("answered".equals(status)
        ? answerSha256 == null || !answerSha256.matches("[a-f0-9]{64}") || reason != null || references.isEmpty()
        : answerSha256 != null || !references.isEmpty() || reason == null || !reason.matches("[a-z][a-z0-9_]{0,63}")) {
      throw ModelValues.invalid();
    }
    for (int i = 0; i < references.size(); i++) {
      if (references.get(i) == null || references.get(i).citationId() != i + 1) {
        throw ModelValues.invalid();
      }
    }
    references = List.copyOf(references);
  }

  @Override
  public String toString() {
    return "KnowledgeTraceDraft[redacted]";
  }
}
