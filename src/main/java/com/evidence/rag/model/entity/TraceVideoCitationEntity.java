package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoTraceEvidence;

/** Frozen publication, real group and member hashes; never generated prose or fake text pages. */
public record TraceVideoCitationEntity(
    VideoTraceEvidence evidence,
    String publicationId,
    String groupId,
    String frameId,
    String transcriptSpanId,
    String sourceSha256,
    String manifestSha256,
    String frameSha256,
    String spanTextSha256,
    String quoteSha256) {
  public TraceVideoCitationEntity {
    ModelValues.identifier(publicationId, 128);
    if (evidence == null
        || groupId == null
        || !groupId.matches("video-group-[a-f0-9]{64}")
        || sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    if (evidence.kind() == VideoTraceEvidence.Kind.VISUAL
        ? frameId == null
            || !frameId.matches("video-frame-[a-f0-9]{64}")
            || frameSha256 == null
            || !frameSha256.matches("[a-f0-9]{64}")
            || transcriptSpanId != null
            || spanTextSha256 != null
            || quoteSha256 != null
        : transcriptSpanId == null
            || !transcriptSpanId.matches("video-transcript-[a-f0-9]{64}")
            || spanTextSha256 == null
            || !spanTextSha256.matches("[a-f0-9]{64}")
            || quoteSha256 == null
            || !quoteSha256.matches("[a-f0-9]{64}")
            || frameId != null
            || frameSha256 != null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "TraceVideoCitationEntity[redacted]";
  }
}
