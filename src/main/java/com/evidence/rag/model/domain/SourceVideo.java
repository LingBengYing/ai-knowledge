package com.evidence.rag.model.domain;

/** Current-authorized original video bytes pinned to a published source revision. */
public record SourceVideo(String mediaType, byte[] content) {
  public SourceVideo {
    if (mediaType == null
        || !mediaType.matches("video/(mp4|quicktime|webm|x-matroska)")
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
    return "SourceVideo[redacted]";
  }
}
