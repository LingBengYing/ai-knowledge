package com.evidence.rag.model.domain;

/** One fact's actual published video member; visual proof has no character locator. */
public record VideoTraceEvidence(
    int citationOrdinal,
    Kind kind,
    String physicalSegmentId,
    Integer startCodePoint,
    Integer endCodePoint,
    double retrievalScore,
    double rerankScore,
    String factSha256) {
  public enum Kind {
    VISUAL,
    TRANSCRIPT
  }

  public VideoTraceEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    if (citationOrdinal < 1
        || citationOrdinal > 32
        || kind == null
        || !Double.isFinite(retrievalScore)
        || !Double.isFinite(rerankScore)
        || factSha256 == null
        || !factSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    if (kind == Kind.VISUAL
        ? startCodePoint != null || endCodePoint != null
        : startCodePoint == null
            || endCodePoint == null
            || startCodePoint < 0
            || endCodePoint <= startCodePoint
            || endCodePoint - startCodePoint > 1200) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoTraceEvidence[redacted]";
  }
}
