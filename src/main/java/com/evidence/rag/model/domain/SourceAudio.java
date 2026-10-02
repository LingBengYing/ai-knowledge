package com.evidence.rag.model.domain;

import java.util.Set;

/** Same-version original audio bytes, returned only after current citation authorization. */
public record SourceAudio(String mimeType, byte[] content) {
  private static final Set<String> MEDIA_TYPES =
      Set.of("audio/wav", "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/webm");

  public SourceAudio {
    if (mimeType == null
        || !MEDIA_TYPES.contains(mimeType)
        || content == null
        || content.length == 0
        || content.length > 20 * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public String toString() {
    return "SourceAudio[redacted]";
  }
}
