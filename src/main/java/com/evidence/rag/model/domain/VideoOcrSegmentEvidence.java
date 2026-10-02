package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Frame-local OCR chunk identity, separate from description and transcript identities. */
public record VideoOcrSegmentEvidence(
    String id, String revisionId, String frameId, VideoOcrSegment segment) {
  public VideoOcrSegmentEvidence {
    ModelValues.indexIdentity(revisionId);
    if (segment == null
        || frameId == null
        || !frameId.matches("video-frame-[a-f0-9]{64}")
        || !identity(revisionId, frameId, segment.ordinal()).equals(id)) {
      throw ModelValues.invalid();
    }
  }

  public static String identity(String revisionId, String frameId, int ordinal) {
    ModelValues.indexIdentity(revisionId);
    if (frameId == null || !frameId.matches("video-frame-[a-f0-9]{64}") || ordinal < 0) {
      throw ModelValues.invalid();
    }
    return "video-ocr-"
        + ModelValues.sha256(
            (revisionId + "\0" + frameId + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }
}
