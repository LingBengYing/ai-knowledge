package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Lightweight published recall material; description text is not visual proof. */
public record PublishedVideoCandidate(
    PublicationVersion publication,
    VideoTraceEvidence.Kind kind,
    String physicalSegmentId,
    String sourceId,
    String entrySha256,
    String recallText,
    String filename,
    String mediaType) {
  public PublishedVideoCandidate {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(filename, 255);
    if (publication == null
        || kind == null
        || !publication.parserRevision().matches(
            kind == VideoTraceEvidence.Kind.TRANSCRIPT
                ? "java-video-compiler-v[1234]:[a-f0-9]{64}"
                : "java-video-compiler-v[123]:[a-f0-9]{64}")
        || sourceId == null
        || !sourceId.matches(
            kind == VideoTraceEvidence.Kind.VISUAL
                ? "video-frame-[a-f0-9]{64}"
                : "video-transcript-[a-f0-9]{64}")
        || physicalSegmentId.equals(sourceId)
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || recallText == null
        || recallText.isBlank()
        || recallText.codePointCount(0, recallText.length()) > 4096
        || recallText.getBytes(StandardCharsets.UTF_8).length > 16384
        || recallText
            .codePoints()
            .anyMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF))
        || mediaType == null
        || !mediaType.matches("video/(mp4|quicktime|webm|x-matroska)")) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "PublishedVideoCandidate[redacted]";
  }
}
