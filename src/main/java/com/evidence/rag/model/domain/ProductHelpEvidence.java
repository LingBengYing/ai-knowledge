package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** An authority-owned original excerpt; retrieval scores never make it a generated answer. */
public record ProductHelpEvidence(
    PublicationVersion publication,
    String physicalId,
    String filename,
    String mediaType,
    Kind kind,
    String text,
    Integer page,
    Integer start,
    Integer end,
    Long startUs,
    Long endUs,
    String origin) {
  public enum Kind {
    DOCUMENT_TEXT,
    VIDEO_TRANSCRIPT,
    VIDEO_SUBTITLE,
    VIDEO_FRAME_OCR
  }

  public ProductHelpEvidence {
    ModelValues.identifier(physicalId, 128);
    ModelValues.identifier(filename, 255);
    ModelValues.identifier(mediaType, 100);
    if (publication == null
        || kind == null
        || text == null
        || text.isBlank()
        || text.codePointCount(0, text.length()) > 4096
        || text.getBytes(StandardCharsets.UTF_8).length > 16384
        || text.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))) {
      throw ModelValues.invalid();
    }
    if (!publication.documentId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
      throw ModelValues.invalid();
    }
    ModelValues.indexIdentity(publication.sourceRevisionId());
    if (kind == Kind.DOCUMENT_TEXT) {
      if (page == null
          || page < 1
          || page > 500
          || start == null
          || start < 0
          || end == null
          || end <= start
          || end - start > 1200
          || end - start != text.codePointCount(0, text.length())
          || startUs != null
          || endUs != null
          || !("source_text".equals(origin) || "machine_ocr".equals(origin))) {
        throw ModelValues.invalid();
      }
    } else if (page != null
        || start != null
        || end != null
        || startUs == null
        || endUs == null
        || startUs < 0
        || endUs <= startUs
        || endUs > 600_000_000L
        || !mediaType.startsWith("video/")
        || !(switch (kind) {
          case VIDEO_TRANSCRIPT -> "machine_asr".equals(origin);
          case VIDEO_SUBTITLE -> "embedded_subtitle".equals(origin);
          case VIDEO_FRAME_OCR -> "machine_ocr".equals(origin);
          default -> false;
        })) {
      throw ModelValues.invalid();
    }
  }

  public String textSha256() {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  public String category() {
    return kind == Kind.DOCUMENT_TEXT ? "document" : "video";
  }

  public String timePrecision() {
    return switch (kind) {
      case DOCUMENT_TEXT -> null;
      case VIDEO_TRANSCRIPT -> "server_chunk";
      case VIDEO_SUBTITLE -> "subtitle_cue";
      case VIDEO_FRAME_OCR -> "frame_interval";
    };
  }

  @Override
  public String toString() {
    return "ProductHelpEvidence[redacted]";
  }
}
