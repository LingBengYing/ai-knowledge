package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

public record VideoAvCompilation(
    String sourceSha256,
    String decoderRevision,
    VideoAvEpoch epoch,
    long durationTick,
    boolean hasAudio,
    List<VideoAvWindow> windows) {
  public static final int MAX_WINDOWS = 1201;
  public static final long MAX_CLIP_BYTES = 64L * 1024 * 1024;

  public VideoAvCompilation {
    VideoAvProfile.hash(sourceSha256);
    ModelValues.identifier(decoderRevision, 200);
    if (epoch == null
        || durationTick < 1
        || durationTick > epoch.durationLimit(600)
        || windows == null
        || windows.isEmpty()
        || windows.size() > MAX_WINDOWS) {
      throw ModelValues.invalid();
    }
    long cursor = 0, samples = 0, bytes = 0;
    int sourceOrdinal = 0;
    boolean audioFound = false, videoFound = false;
    var ids = new HashSet<String>();
    for (int i = 0; i < windows.size(); i++) {
      var w = windows.get(i);
      if (w == null
          || w.ordinal() != i
          || w.startTick() != cursor
          || w.endTick() - cursor > epoch.durationLimit(30)
          || !ids.add(w.id())) {
        throw ModelValues.invalid();
      }
      if (w.video() != null) {
        videoFound = true;
        bytes += w.video().content().length;
        for (var f : w.video().frames()) {
          if (f.sourceOrdinal() != sourceOrdinal++) {
            throw ModelValues.invalid();
          }
        }
      }
      if (w.audio() != null) {
        audioFound = true;
        var a = w.audio();
        if (!hasAudio
            || !a.sourceSha256().equals(sourceSha256)
            || !a.decoderRevision().equals(decoderRevision)
            || a.startSample() != samples
            || a.startSample() != epoch.sampleAt(w.startTick())
            || a.endSample() > epoch.sampleAt(w.endTick())) {
          throw ModelValues.invalid();
        }
        samples = a.endSample();
      }
      cursor = w.endTick();
    }
    if (cursor != durationTick
        || !videoFound
        || hasAudio != audioFound
        || bytes > MAX_CLIP_BYTES
        || samples > 9600000) {
      throw ModelValues.invalid();
    }
    windows = List.copyOf(windows);
  }

  @Override
  public String toString() {
    return "VideoAvCompilation[redacted]";
  }
}
