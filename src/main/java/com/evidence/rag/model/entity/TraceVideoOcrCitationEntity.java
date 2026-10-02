package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceEvidence;

/** Hash-only locator for OCR; it never contributes to a v11 visual/transcript proof. */
public record TraceVideoOcrCitationEntity(
    TraceEvidence evidence,
    String publicationId,
    String segmentId,
    String frameId,
    String sourceSha256,
    String frameSha256,
    String ocrManifestSha256,
    String frameTextSha256,
    String quoteSha256) {
  public TraceVideoOcrCitationEntity {
    ModelValues.indexIdentity(publicationId);
    if (evidence == null
        || segmentId == null
        || !segmentId.matches("video-ocr-[a-f0-9]{64}")
        || frameId == null
        || !frameId.matches("video-frame-[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    for (String value :
        new String[] {sourceSha256, frameSha256, ocrManifestSha256, frameTextSha256, quoteSha256}) {
      if (value == null || !value.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
  }

  @Override
  public String toString() {
    return "TraceVideoOcrCitationEntity[redacted]";
  }
}
