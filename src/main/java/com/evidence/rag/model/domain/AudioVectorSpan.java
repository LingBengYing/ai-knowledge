package com.evidence.rag.model.domain;

/** A saved nonempty speech span and its complete original PCM slice. */
public record AudioVectorSpan(
    String audioEvidenceId,
    String basePhysicalSegmentId,
    int ordinal,
    long startMs,
    long endMs,
    AudioWaveform waveform) {
  public AudioVectorSpan {
    ModelValues.indexIdentity(audioEvidenceId);
    ModelValues.identifier(basePhysicalSegmentId, 128);
    if (ordinal < 0
        || ordinal >= 600
        || startMs < 0
        || endMs <= startMs
        || endMs > 600000
        || endMs - startMs > 30000
        || waveform == null
        || waveform.startSample() != startMs * 16
        || waveform.endSample() > (endMs * 16)
        || waveform.endSample() <= (endMs - 1) * 16) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "AudioVectorSpan[redacted]";
  }
}
