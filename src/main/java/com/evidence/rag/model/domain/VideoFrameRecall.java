package com.evidence.rag.model.domain;

/** Caption is retrieval data attached to a real frame, never proof of a visible fact. */
public record VideoFrameRecall(VideoFrame frame, ImageRecall recall) {
  public VideoFrameRecall {
    if (frame == null || recall == null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoFrameRecall[redacted]";
  }
}
