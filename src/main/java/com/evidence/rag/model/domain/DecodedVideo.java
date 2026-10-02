package com.evidence.rag.model.domain;

import java.util.List;

/** Complete selected original frames and optional aligned PCM, all bound to one source video. */
public record DecodedVideo(
    String sourceSha256,
    String decoderRevision,
    long timelineOriginUs,
    long durationUs,
    List<VideoFrame> frames,
    DecodedAudio audio,
    VideoSubtitleCompilation subtitles) {
  public DecodedVideo(
      String sourceSha256,
      String decoderRevision,
      long timelineOriginUs,
      long durationUs,
      List<VideoFrame> frames,
      DecodedAudio audio) {
    this(sourceSha256, decoderRevision, timelineOriginUs, durationUs, frames, audio, null);
  }

  public DecodedVideo {
    frames = validatedFrames(sourceSha256, decoderRevision, durationUs, frames);
    validateSubtitles(subtitles, timelineOriginUs, durationUs);
    if (audio != null
        && (!sourceSha256.equals(audio.sourceSha256())
            || !decoderRevision.equals(audio.decoderRevision())
            || audio.durationMs() > (durationUs + 999) / 1000)) {
      throw ModelValues.invalid();
    }
  }

  static void validateSubtitles(
      VideoSubtitleCompilation subtitles, long timelineOriginUs, long durationUs) {
    if (subtitles != null
        && (subtitles.timelineOriginUs() != timelineOriginUs || subtitles.endUs() > durationUs)) {
      throw ModelValues.invalid();
    }
  }

  static List<VideoFrame> validatedFrames(
      String sourceSha256, String decoderRevision, long durationUs, List<VideoFrame> frames) {
    if (sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || durationUs < 1
        || durationUs > 600_000_000
        || frames == null
        || frames.isEmpty()
        || frames.size() > 128) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(decoderRevision, 200);
    long bytes = 0;
    long previous = -1;
    for (int index = 0; index < frames.size(); index++) {
      var frame = frames.get(index);
      if (frame == null
          || frame.ordinal() != index
          || (index == 0 ? frame.presentationUs() != 0 : frame.presentationUs() <= previous)
          || frame.presentationUs() > durationUs - frame.durationUs()) {
        throw ModelValues.invalid();
      }
      previous = frame.presentationUs();
      bytes += frame.image().content().length;
      if (bytes > 32L * 1024 * 1024) {
        throw ModelValues.invalid();
      }
    }
    return List.copyOf(frames);
  }

  @Override
  public String toString() {
    return "DecodedVideo[redacted]";
  }
}
