package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Machine transcript of a server-cut time span; no invented speaker or confidence. */
public record AudioTranscriptSpan(int ordinal, long startMs, long endMs, String text) {
  public AudioTranscriptSpan {
    if (ordinal < 0
        || ordinal >= 600
        || startMs < 0
        || endMs <= startMs
        || endMs > 600_000
        || endMs - startMs > 30_000
        || text == null
        || text.codePointCount(0, text.length()) > 4096
        || text.getBytes(StandardCharsets.UTF_8).length > 16384
        || text.codePoints()
            .anyMatch(
                c ->
                    (c >= 0xD800 && c <= 0xDFFF)
                        || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))) {
      throw ModelValues.invalid();
    }
  }

  public String textSha256() {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "AudioTranscriptSpan[redacted]";
  }
}
