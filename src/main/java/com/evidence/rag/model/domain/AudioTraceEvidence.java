package com.evidence.rag.model.domain;

import java.util.List;

/** Transcript code-point excerpt proposed for authority binding to a real server-cut span. */
public record AudioTraceEvidence(
    int citationOrdinal,
    String physicalSegmentId,
    int startCodePoint,
    int endCodePoint,
    double retrievalScore,
    double rerankScore,
    List<String> factSha256) {
  public AudioTraceEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    if (citationOrdinal < 1
        || citationOrdinal > 32
        || startCodePoint < 0
        || endCodePoint <= startCodePoint
        || endCodePoint - startCodePoint > 1200
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
    return "AudioTraceEvidence[redacted]";
  }
}
