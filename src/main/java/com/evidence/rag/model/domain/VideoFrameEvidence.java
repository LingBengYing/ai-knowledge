package com.evidence.rag.model.domain;

/** One authority-owned original video frame and its recall-only description. */
public record VideoFrameEvidence(String id, String revisionId, VideoFrameRecall material) {
  public VideoFrameEvidence {
    ModelValues.indexIdentity(revisionId);
    if (material == null
        || !VideoEvidence.frameIdentity(revisionId, material.frame().ordinal()).equals(id)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoFrameEvidence[redacted]";
  }
}
