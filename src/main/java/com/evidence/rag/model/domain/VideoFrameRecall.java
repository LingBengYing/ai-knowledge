package com.evidence.rag.model.domain;

/** Optional caption is recall-only; the enclosing compilation enforces its explicit mode. */
public record VideoFrameRecall(VideoFrame frame, ImageRecall recall) {
  public VideoFrameRecall {
    if (frame == null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoFrameRecall[redacted]";
  }
}
