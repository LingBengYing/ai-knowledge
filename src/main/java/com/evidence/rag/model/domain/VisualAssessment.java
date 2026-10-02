package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Model support judgment only; this result is not an authorized answer or source locator. */
public record VisualAssessment(
    List<String> claims,
    String sourceSha256,
    String modelRevision,
    String policyRevision,
    String refusalReason) {
  private static final Set<String> REFUSAL_REASONS =
      Set.of(
          "model_refused",
          "incomplete_evidence",
          "unsupported_claims",
          "model_failure",
          "configuration_changed",
          "processing_interrupted");

  public VisualAssessment {
    if (claims == null
        || sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || (refusalReason == null
            ? claims.isEmpty() || claims.size() > 8
            : !REFUSAL_REASONS.contains(refusalReason) || !claims.isEmpty())) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    for (String claim : claims) {
      if (claim == null
          || claim.isBlank()
          || claim.codePointCount(0, claim.length()) > 1024
          || claim.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
        throw ModelValues.invalid();
      }
    }
    if (new HashSet<>(claims).size() != claims.size()) {
      throw ModelValues.invalid();
    }
    claims = List.copyOf(claims);
  }

  @Override
  public String toString() {
    return "VisualAssessment[redacted]";
  }
}
