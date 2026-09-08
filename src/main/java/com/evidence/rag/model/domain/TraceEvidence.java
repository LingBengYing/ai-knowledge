package com.evidence.rag.model.domain;

import java.util.List;

/** Server-validated citation locator and scores; fact text is represented only by hashes. */
public record TraceEvidence(
    int citationOrdinal,
    String physicalSegmentId,
    int start,
    int end,
    double retrievalScore,
    double rerankScore,
    List<String> factSha256) {
  public TraceEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    if (citationOrdinal < 1
        || citationOrdinal > 32
        || start < 0
        || end <= start
        || end - start > 1200
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
}
