package com.evidence.rag.model.domain;

/** A video-owned ASR span, including silence and its optional projection ordinal. */
public record VideoTranscriptEvidence(
    String id, String revisionId, AudioTranscriptSpan span, Integer indexOrdinal) {
  public VideoTranscriptEvidence {
    ModelValues.indexIdentity(revisionId);
    if (span == null
        || !VideoEvidence.transcriptIdentity(revisionId, span.ordinal()).equals(id)
        || (span.text().isBlank() ? indexOrdinal != null : indexOrdinal == null)
        || (indexOrdinal != null && (indexOrdinal < 0 || indexOrdinal >= 600))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoTranscriptEvidence[redacted]";
  }
}
