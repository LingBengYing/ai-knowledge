package com.evidence.rag.model.domain;

import java.util.List;

public record VideoAvClip(
    byte[] content,
    String sha256,
    long firstLocalTick,
    long endLocalTick,
    List<VideoAvFrameTiming> frames,
    String framesManifestSha256) {
  public static final int MAX_BYTES = 8 * 1024 * 1024;

  public VideoAvClip {
    VideoAvProfile.hash(sha256);
    VideoAvProfile.hash(framesManifestSha256);
    if (content == null
        || content.length < 1
        || content.length > MAX_BYTES
        || !ModelValues.sha256(content).equals(sha256)
        || frames == null
        || frames.isEmpty()
        || firstLocalTick < 0
        || endLocalTick <= firstLocalTick) {
      throw ModelValues.invalid();
    }
    long end = firstLocalTick;
    int ordinal = frames.getFirst().sourceOrdinal();
    int width = frames.getFirst().width(), height = frames.getFirst().height();
    for (var frame : frames) {
      if (frame == null
          || frame.sourceOrdinal() != ordinal++
          || frame.localTick() != end
          || frame.width() != width
          || frame.height() != height) {
        throw ModelValues.invalid();
      }
      end = Math.addExact(frame.localTick(), frame.durationTick());
    }
    if (end != endLocalTick
        || !VideoAvProfile.framesManifestSha256(frames).equals(framesManifestSha256)) {
      throw ModelValues.invalid();
    }
    content = content.clone();
    frames = List.copyOf(frames);
  }

  public int frameCount() {
    return frames.size();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public String toString() {
    return "VideoAvClip[redacted]";
  }
}
