package com.evidence.rag.model.domain;

/** One actual complete decoder window, including silence. */
public record SoundInputSpan(String id, int ordinal, AudioWaveform waveform) {
  public SoundInputSpan {
    ModelValues.indexIdentity(id);
    if (ordinal < 0 || ordinal > 599 || waveform == null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "SoundInputSpan[redacted]";
  }
}
