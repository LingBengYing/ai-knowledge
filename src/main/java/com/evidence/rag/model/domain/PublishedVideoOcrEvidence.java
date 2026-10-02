package com.evidence.rag.model.domain;

/** Authorized OCR material includes complete frame text and exact chunk coordinates, not pixels. */
public record PublishedVideoOcrEvidence(
    PublicationVersion publication,
    String physicalSegmentId,
    String entrySha256,
    VideoOcrSegmentEvidence source,
    VideoFrameOcr frame,
    long framePresentationUs,
    long frameDurationUs,
    String ocrRevision,
    String ocrManifestSha256,
    String filename,
    String mediaType) {
  public PublishedVideoOcrEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(filename, 255);
    ModelValues.identifier(ocrRevision, 200);
    if (publication == null
        || source == null
        || frame == null
        || !publication.parserRevision().matches("java-video-compiler-v[23]:[a-f0-9]{64}")
        || !publication.sourceRevisionId().equals(source.revisionId())
        || !VideoEvidence.frameIdentity(source.revisionId(), frame.frameOrdinal())
            .equals(source.frameId())
        || physicalSegmentId.equals(source.id())
        || !frame.segments().contains(source.segment())
        || framePresentationUs < 0
        || frameDurationUs < 1
        || framePresentationUs > 600_000_000L - frameDurationUs
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || ocrManifestSha256 == null
        || !ocrManifestSha256.matches("[a-f0-9]{64}")
        || mediaType == null
        || !mediaType.matches("video/(mp4|quicktime|webm|x-matroska)")) {
      throw ModelValues.invalid();
    }
  }

  public GroundingText grounding() {
    return new GroundingText(
        physicalSegmentId,
        publication.publicationId() + ":video-frame-ocr:" + source.frameId(),
        frame.text(),
        ModelValues.sha256(frame.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        source.segment().start(),
        source.segment().end());
  }

  @Override
  public String toString() {
    return "PublishedVideoOcrEvidence[redacted]";
  }
}
