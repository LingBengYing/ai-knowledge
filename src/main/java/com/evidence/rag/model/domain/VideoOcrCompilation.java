package com.evidence.rag.model.domain;

import java.util.List;

/** The complete OCR result for every selected frame under one frozen OCR implementation. */
public record VideoOcrCompilation(String ocrRevision, List<VideoFrameOcr> frames) {
  public VideoOcrCompilation {
    ModelValues.identifier(ocrRevision, 200);
    if (frames == null || frames.isEmpty() || frames.size() > 128) {
      throw ModelValues.invalid();
    }
    long segmentPoints = 0;
    for (int ordinal = 0; ordinal < frames.size(); ordinal++) {
      var frame = frames.get(ordinal);
      if (frame == null || frame.frameOrdinal() != ordinal) {
        throw ModelValues.invalid();
      }
      for (var segment : frame.segments()) {
        segmentPoints += segment.end() - segment.start();
      }
      if (segmentPoints > 1_500_000) {
        throw ModelValues.invalid();
      }
    }
    frames = List.copyOf(frames);
  }

  @Override
  public String toString() {
    return "VideoOcrCompilation[redacted]";
  }
}
