package com.evidence.rag.model.domain;

public record VideoAvWindow(
    String id, int ordinal, long startTick, long endTick, VideoAvClip video, AudioWaveform audio) {
  public VideoAvWindow {
    ModelValues.indexIdentity(id);
    if (ordinal < 0
        || ordinal >= VideoAvCompilation.MAX_WINDOWS
        || startTick < 0
        || endTick <= startTick
        || (video == null && audio == null)
        || (video != null && video.endLocalTick() > endTick - startTick)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoAvWindow[redacted]";
  }
}
