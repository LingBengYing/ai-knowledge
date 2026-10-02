package com.evidence.rag.model.domain;

/** A proved transcript excerpt mapped to the whole server-cut span, never interpolated time. */
public record AudioSourceEvidence(
    PublishedAudioEvidence evidence, int startCodePoint, int endCodePoint, SourceAudio audio) {
  public AudioSourceEvidence {
    if (evidence == null
        || startCodePoint < evidence.transcript().startCodePoint()
        || endCodePoint > evidence.transcript().endCodePoint()
        || startCodePoint >= endCodePoint
        || endCodePoint - startCodePoint > 1200
        || audio != null
            && (!audio.mimeType().equals(evidence.mediaType())
                || !ModelValues.sha256(audio.content())
                    .equals(evidence.publication().sourceSha256()))) {
      throw ModelValues.invalid();
    }
  }

  public AudioSourceEvidence(
      PublishedAudioEvidence evidence, int startCodePoint, int endCodePoint) {
    this(evidence, startCodePoint, endCodePoint, null);
  }

  public String quote() {
    String text = evidence.transcript().contextText();
    return text.substring(
        text.offsetByCodePoints(0, startCodePoint), text.offsetByCodePoints(0, endCodePoint));
  }

  public long startMs() {
    return evidence.span().startMs();
  }

  public long endMs() {
    return evidence.span().endMs();
  }

  @Override
  public String toString() {
    return "AudioSourceEvidence[redacted]";
  }
}
