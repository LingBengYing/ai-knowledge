package com.evidence.rag.model.domain;

import java.util.List;

/** Complete ordered transcription, including silent spans; not a published library revision. */
public record AudioCompilation(
    String sourceSha256,
    String decoderRevision,
    String modelRevision,
    String compilerRevision,
    long durationMs,
    List<AudioTranscriptSpan> spans) {
  public AudioCompilation {
    if (sourceSha256 == null
        || !sourceSha256.matches("[0-9a-f]{64}")
        || durationMs < 1
        || durationMs > 600_000
        || spans == null
        || spans.isEmpty()
        || spans.size() > 600) {
      throw ModelValues.invalid();
    }
    decoderRevision = ModelValues.identifier(decoderRevision, 200);
    modelRevision = ModelValues.identifier(modelRevision, 200);
    compilerRevision = ModelValues.identifier(compilerRevision, 200);
    spans = List.copyOf(spans);
    long end = 0;
    long codePoints = 0;
    boolean hasTranscript = false;
    for (int index = 0; index < spans.size(); index++) {
      var span = spans.get(index);
      if (span.ordinal() != index || span.startMs() != end) {
        throw ModelValues.invalid();
      }
      end = span.endMs();
      codePoints += span.text().codePointCount(0, span.text().length());
      hasTranscript |= !span.text().isBlank();
    }
    if (end != durationMs || codePoints > 1_000_000 || !hasTranscript) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "AudioCompilation[redacted]";
  }
}
