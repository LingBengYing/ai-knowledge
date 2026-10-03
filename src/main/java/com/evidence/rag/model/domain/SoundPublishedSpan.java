package com.evidence.rag.model.domain;

/** One exact member of a sealed sound publication. */
public record SoundPublishedSpan(SoundPublication publication, SoundSpan span) {
  public SoundPublishedSpan {
    if (publication == null || span == null || !publication.spans().contains(span)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "SoundPublishedSpan[redacted]";
  }
}
