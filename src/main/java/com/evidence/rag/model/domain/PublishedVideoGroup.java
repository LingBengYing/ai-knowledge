package com.evidence.rag.model.domain;

/** Lightweight real group and source-to-physical publication identities, without image bytes. */
public record PublishedVideoGroup(
    PublicationVersion publication,
    VideoEvidenceGroup group,
    String framePhysicalSegmentId,
    String transcriptPhysicalSegmentId,
    String manifestSha256,
    String filename,
    String mediaType) {
  public PublishedVideoGroup {
    ModelValues.identifier(filename, 255);
    if (publication == null
        || group == null
        || !publication.sourceRevisionId().equals(group.revisionId())
        || !publication.parserRevision().matches("java-video-compiler-v[123]:[a-f0-9]{64}")
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")
        || mediaType == null
        || !mediaType.matches("video/(mp4|quicktime|webm|x-matroska)")
        || (group.frameId() == null) != (framePhysicalSegmentId == null)
        || (group.transcriptSpanId() == null && transcriptPhysicalSegmentId != null)) {
      throw ModelValues.invalid();
    }
    if (framePhysicalSegmentId != null) {
      ModelValues.identifier(framePhysicalSegmentId, 128);
      if (framePhysicalSegmentId.equals(group.frameId())) {
        throw ModelValues.invalid();
      }
    }
    if (transcriptPhysicalSegmentId != null) {
      ModelValues.identifier(transcriptPhysicalSegmentId, 128);
      if (transcriptPhysicalSegmentId.equals(group.transcriptSpanId())
          || transcriptPhysicalSegmentId.equals(framePhysicalSegmentId)) {
        throw ModelValues.invalid();
      }
    }
  }

  @Override
  public String toString() {
    return "PublishedVideoGroup[redacted]";
  }
}
