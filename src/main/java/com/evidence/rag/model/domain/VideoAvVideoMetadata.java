package com.evidence.rag.model.domain;

public record VideoAvVideoMetadata(
    String clipSha256,
    int frameCount,
    String framesManifestSha256,
    long firstLocalTick,
    long endLocalTick) {
  public VideoAvVideoMetadata {
    VideoAvProfile.hash(clipSha256);
    VideoAvProfile.hash(framesManifestSha256);
    if (frameCount < 1 || firstLocalTick < 0 || endLocalTick <= firstLocalTick) {
      throw ModelValues.invalid();
    }
  }

  public static VideoAvVideoMetadata from(VideoAvClip c) {
    return c == null
        ? null
        : new VideoAvVideoMetadata(
            c.sha256(),
            c.frameCount(),
            c.framesManifestSha256(),
            c.firstLocalTick(),
            c.endLocalTick());
  }
}
