package com.evidence.rag.model.domain;

import java.util.List;

/** Complete PCM-relative machine transcript; an empty spoken track is not itself a failure. */
public record AudioTranscription(
    String sourceSha256,
    String decoderRevision,
    String modelRevision,
    String transcriptionRevision,
    long sampleCount,
    List<AudioTranscriptSpan> spans) {
  public AudioTranscription {
    if (sourceSha256 == null
        || !sourceSha256.matches("[0-9a-f]{64}")
        || sampleCount < 1
        || sampleCount > 9_600_000
        || spans == null
        || spans.isEmpty()
        || spans.size() > 600) {
      throw ModelValues.invalid();
    }
    decoderRevision = ModelValues.identifier(decoderRevision, 200);
    modelRevision = ModelValues.identifier(modelRevision, 200);
    transcriptionRevision = ModelValues.identifier(transcriptionRevision, 200);
    spans = List.copyOf(spans);
    long end = 0;
    long codePoints = 0;
    for (int index = 0; index < spans.size(); index++) {
      var span = spans.get(index);
      if (span.ordinal() != index || span.startMs() != end) {
        throw ModelValues.invalid();
      }
      end = span.endMs();
      codePoints += span.text().codePointCount(0, span.text().length());
    }
    if (end != (sampleCount + 15L) / 16 || codePoints > 1_000_000) {
      throw ModelValues.invalid();
    }
  }

  public long durationMs() {
    return (sampleCount + 15L) / 16;
  }

  @Override
  public String toString() {
    return "AudioTranscription[redacted]";
  }
}
