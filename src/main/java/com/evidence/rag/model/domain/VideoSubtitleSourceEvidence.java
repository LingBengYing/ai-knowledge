package com.evidence.rag.model.domain;

/** One authorized subtitle excerpt; times identify a cue, never ASR words or visible pixels. */
public record VideoSubtitleSourceEvidence(
    PublishedVideoSubtitleEvidence source, TraceEvidence trace, SourceVideo video) {
  public VideoSubtitleSourceEvidence {
    if (source == null
        || trace == null
        || !trace.physicalSegmentId().equals(source.physicalSegmentId())
        || trace.start() < source.source().startOffset()
        || trace.end() > source.source().endOffset()
        || (video != null
            && (!video.mediaType().equals(source.mediaType())
                || !ModelValues.sha256(video.content())
                    .equals(source.publication().sourceSha256())))) {
      throw ModelValues.invalid();
    }
  }

  public String quote() {
    String text = source.trackText();
    return text.substring(
        text.offsetByCodePoints(0, trace.start()), text.offsetByCodePoints(0, trace.end()));
  }

  @Override
  public String toString() {
    return "VideoSubtitleSourceEvidence[redacted]";
  }
}
