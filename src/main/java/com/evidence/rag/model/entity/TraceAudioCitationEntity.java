package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.AudioTraceEvidence;
import com.evidence.rag.model.domain.ModelValues;

/** Saved audio identity; time is copied from authority, never estimated from transcript offsets. */
public record TraceAudioCitationEntity(
    AudioTraceEvidence evidence,
    String publicationId,
    String audioSpanId,
    long startMs,
    long endMs,
    String sourceSha256,
    String textSha256,
    String transcriptSha256,
    String quoteSha256) {
  public TraceAudioCitationEntity {
    ModelValues.identifier(publicationId, 128);
    ModelValues.identifier(audioSpanId, 128);
    if (evidence == null
        || startMs < 0
        || endMs <= startMs
        || endMs > 600_000
        || endMs - startMs > 30_000) {
      throw ModelValues.invalid();
    }
    for (String hash : new String[] {sourceSha256, textSha256, transcriptSha256, quoteSha256}) {
      if (hash == null || !hash.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
  }

  @Override
  public String toString() {
    return "TraceAudioCitationEntity[redacted]";
  }
}
