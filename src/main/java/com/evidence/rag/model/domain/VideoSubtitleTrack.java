package com.evidence.rag.model.domain;

import java.util.List;

/** All original packets from one actual subtitle stream, in packet order. */
public record VideoSubtitleTrack(
    int streamIndex,
    String codec,
    long timeBaseNumerator,
    long timeBaseDenominator,
    String language,
    List<VideoSubtitleCue> cues) {
  public VideoSubtitleTrack {
    if (streamIndex < 0
        || (!"mov_text".equals(codec) && !"subrip".equals(codec) && !"webvtt".equals(codec))
        || timeBaseNumerator <= 0
        || timeBaseDenominator <= 0
        || cues == null
        || cues.size() > 2048) {
      throw ModelValues.invalid();
    }
    if (language != null) {
      ModelValues.identifier(language, 63);
    }
    for (int ordinal = 0; ordinal < cues.size(); ordinal++) {
      var cue = cues.get(ordinal);
      if (cue == null || cue.ordinal() != ordinal) {
        throw ModelValues.invalid();
      }
    }
    cues = List.copyOf(cues);
  }

  @Override
  public String toString() {
    return "VideoSubtitleTrack[redacted]";
  }
}
