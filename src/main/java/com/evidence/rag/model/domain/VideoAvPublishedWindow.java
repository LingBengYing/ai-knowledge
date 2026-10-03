package com.evidence.rag.model.domain;

public record VideoAvPublishedWindow(
    String id,
    int ordinal,
    long startTick,
    long endTick,
    VideoAvVideoMetadata video,
    VideoAvAudioMetadata audio,
    String visualPhysicalId,
    String visualEntrySha256,
    String audioPhysicalId,
    String audioEntrySha256) {
  public VideoAvPublishedWindow {
    ModelValues.indexIdentity(id);
    if (ordinal < 0
        || ordinal >= 1201
        || startTick < 0
        || endTick <= startTick
        || (video == null && audio == null)
        || (video != null && video.endLocalTick() > endTick - startTick)) {
      throw ModelValues.invalid();
    }
    route(video != null, visualPhysicalId, visualEntrySha256);
    route(audio != null, audioPhysicalId, audioEntrySha256);
  }

  private static void route(boolean present, String physical, String digest) {
    if (present) {
      ModelValues.indexIdentity(physical);
      VideoAvProfile.hash(digest);
    } else if (physical != null || digest != null) {
      throw ModelValues.invalid();
    }
  }
}
