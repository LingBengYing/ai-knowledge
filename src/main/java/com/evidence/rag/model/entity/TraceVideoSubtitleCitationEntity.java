package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceEvidence;

/** Hash-only subtitle locator, separate from audio, OCR and visual/transcript group proofs. */
public record TraceVideoSubtitleCitationEntity(
    TraceEvidence evidence,
    String publicationId,
    String cueId,
    String trackId,
    String sourceSha256,
    String subtitleManifestSha256,
    String nativeManifestSha256,
    String trackTextSha256,
    String payloadSha256,
    String quoteSha256) {
  public TraceVideoSubtitleCitationEntity {
    ModelValues.indexIdentity(publicationId);
    if (evidence == null
        || cueId == null
        || !cueId.matches("video-subtitle-cue-[a-f0-9]{64}")
        || trackId == null
        || !trackId.matches("video-subtitle-track-[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    for (String hash :
        new String[] {
          sourceSha256,
          subtitleManifestSha256,
          nativeManifestSha256,
          trackTextSha256,
          payloadSha256,
          quoteSha256
        }) {
      if (hash == null || !hash.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
  }

  @Override
  public String toString() {
    return "TraceVideoSubtitleCitationEntity[redacted]";
  }
}
