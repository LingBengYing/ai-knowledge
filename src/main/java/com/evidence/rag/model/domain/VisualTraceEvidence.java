package com.evidence.rag.model.domain;

import java.util.List;

/** Image assessment identity and fact hashes, never a generated text locator. */
public record VisualTraceEvidence(
    int citationOrdinal,
    String physicalSegmentId,
    double retrievalScore,
    double rerankScore,
    List<String> factSha256,
    String visualModelRevision,
    String visualPolicyRevision) {
  public VisualTraceEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(visualModelRevision, 200);
    ModelValues.identifier(visualPolicyRevision, 200);
    if (citationOrdinal < 1
        || citationOrdinal > 32
        || !Double.isFinite(retrievalScore)
        || !Double.isFinite(rerankScore)
        || factSha256 == null
        || factSha256.isEmpty()
        || factSha256.size() > 8) {
      throw ModelValues.invalid();
    }
    for (String hash : factSha256) {
      if (hash == null || !hash.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
    factSha256 = List.copyOf(factSha256);
  }

  @Override
  public String toString() {
    return "VisualTraceEvidence[redacted]";
  }
}
