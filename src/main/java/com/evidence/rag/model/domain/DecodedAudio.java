package com.evidence.rag.model.domain;

/** Actual decoded 16 kHz, mono, signed 16-bit little-endian PCM, not provider metadata. */
public record DecodedAudio(String sourceSha256, String decoderRevision, byte[] pcm) {
  public static final int BYTES_PER_SECOND = 32_000;
  public static final int MAX_BYTES = 600 * BYTES_PER_SECOND;

  public DecodedAudio {
    if (sourceSha256 == null
        || !sourceSha256.matches("[0-9a-f]{64}")
        || pcm == null
        || pcm.length < 2
        || pcm.length > MAX_BYTES
        || pcm.length % 2 != 0) {
      throw ModelValues.invalid();
    }
    decoderRevision = ModelValues.identifier(decoderRevision, 200);
    pcm = pcm.clone();
  }

  @Override
  public byte[] pcm() {
    return pcm.clone();
  }

  public long durationMs() {
    return (pcm.length + 31L) / 32;
  }

  @Override
  public String toString() {
    return "DecodedAudio[redacted]";
  }
}
