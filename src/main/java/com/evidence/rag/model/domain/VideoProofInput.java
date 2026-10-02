package com.evidence.rag.model.domain;

/**
 * Authority-selected original frame and complete transcript context for one real video group. The
 * authority reader must verify parent source/manifest membership and the exact frame/span time
 * intersection: this value has no span timestamps or independent parent lineage to recompute them.
 * A null transcript also represents a retained blank span; it never supplies textual support.
 */
public record VideoProofInput(
    String sourceSha256,
    String manifestSha256,
    VideoEvidenceGroup group,
    VideoFrame frame,
    GroundingText transcript) {
  public VideoProofInput {
    if (sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")
        || group == null
        || (group.frameId() == null) != (frame == null)) {
      throw ModelValues.invalid();
    }
    if (frame != null) {
      if (!VideoEvidence.frameIdentity(group.revisionId(), frame.ordinal()).equals(group.frameId())
          || frame.presentationUs() > group.startUs()
          || frame.presentationUs() > 600_000_000
          || frame.durationUs() > 600_000_000 - frame.presentationUs()
          || frame.presentationUs() + frame.durationUs() < group.endUs()
          || (group.transcriptSpanId() == null
              && (frame.presentationUs() != group.startUs()
                  || frame.presentationUs() + frame.durationUs() != group.endUs()))) {
        throw ModelValues.invalid();
      }
    }
    if (transcript != null
        && (!transcript.physicalId().equals(group.transcriptSpanId())
            || !transcript.contextId().equals("video-transcript:" + group.revisionId())
            || transcript.snippet().isBlank())) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoProofInput[redacted]";
  }
}
