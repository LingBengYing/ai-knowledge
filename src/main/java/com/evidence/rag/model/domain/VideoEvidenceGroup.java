package com.evidence.rag.model.domain;

/** Actual frame/transcript time intersection, or a retained single-modality member. */
public record VideoEvidenceGroup(
    String id,
    String revisionId,
    int ordinal,
    long startUs,
    long endUs,
    String frameId,
    String transcriptSpanId) {
  public VideoEvidenceGroup {
    ModelValues.indexIdentity(revisionId);
    if (ordinal < 0
        || ordinal >= 77_528
        || startUs < 0
        || endUs <= startUs
        || endUs > 600_000_000
        || (frameId == null && transcriptSpanId == null)
        || (frameId != null && !frameId.matches("video-frame-[a-f0-9]{64}"))
        || (transcriptSpanId != null && !transcriptSpanId.matches("video-transcript-[a-f0-9]{64}"))
        || !VideoEvidence.groupIdentity(revisionId, frameId, transcriptSpanId).equals(id)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoEvidenceGroup[redacted]";
  }
}
