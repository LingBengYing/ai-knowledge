package com.evidence.rag.model.domain;

public record VideoAvFrameTiming(
    int sourceOrdinal,
    long localTick,
    long durationTick,
    int width,
    int height,
    String pixelSha256) {
  public VideoAvFrameTiming {
    VideoAvProfile.hash(pixelSha256);
    if (sourceOrdinal < 0
        || localTick < 0
        || durationTick < 1
        || width < 1
        || height < 1
        || (long) width * height > 12000000) {
      throw ModelValues.invalid();
    }
    Math.addExact(localTick, durationTick);
  }
}
