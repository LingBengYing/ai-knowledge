package com.evidence.rag.model.domain;

/** One original subtitle packet, including an empty clear-screen packet. */
public record VideoSubtitleCue(
    int ordinal, long pts, long duration, String text, String payloadSha256) {
  public VideoSubtitleCue {
    if (ordinal < 0
        || ordinal >= 2048
        || duration < 0
        || text == null
        || text.length() > 8192
        || text.codePointCount(0, text.length()) > 4096
        || (!text.isBlank() && duration == 0)
        || payloadSha256 == null
        || !payloadSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    if (text.codePoints()
        .anyMatch(
            point ->
                (Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')
                    || (point >= 0xD800 && point <= 0xDFFF))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoSubtitleCue[redacted]";
  }
}
