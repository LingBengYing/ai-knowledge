package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Immutable sound window; a possibly empty description is recall data, never an ASR quote. */
public record SoundSpan(
    String id,
    int ordinal,
    long startSample,
    long endSample,
    String pcmSha256,
    String recallText,
    String physicalSegmentId,
    String entrySha256) {
  public SoundSpan {
    ModelValues.indexIdentity(id);
    ModelValues.indexIdentity(physicalSegmentId);
    SoundProfile.hash(pcmSha256);
    SoundProfile.hash(entrySha256);
    if (ordinal < 0
        || ordinal > 599
        || startSample < 0
        || endSample <= startSample
        || endSample > 9600000
        || endSample - startSample > 480000
        || recallText == null
        || recallText.getBytes(StandardCharsets.UTF_8).length > 8192) {
      throw ModelValues.invalid();
    }
    ModelValues.bounded(recallText, 8192);
  }

  @Override
  public String toString() {
    return "SoundSpan[redacted]";
  }
}
