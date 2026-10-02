package com.evidence.rag.model.domain;

/** An actual decoded frame on the normalized video time axis, not an estimated frame-rate slot. */
public record VideoFrame(
    int ordinal, long presentationUs, long durationUs, VisualImage image, int width, int height) {
  public VideoFrame {
    if (ordinal < 0
        || ordinal >= 128
        || presentationUs < 0
        || durationUs <= 0
        || image == null
        || width <= 0
        || height <= 0
        || (long) width * height > 12_000_000) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoFrame[redacted]";
  }
}
