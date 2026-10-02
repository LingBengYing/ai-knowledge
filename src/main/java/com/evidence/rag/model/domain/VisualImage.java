package com.evidence.rag.model.domain;

/** Original image input. Metadata admission is performed by the application service. */
public record VisualImage(String mediaType, byte[] content) {
  public VisualImage {
    if (!("image/png".equals(mediaType) || "image/jpeg".equals(mediaType))
        || content == null
        || content.length == 0
        || content.length > 10 * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  public String sha256() {
    return ModelValues.sha256(content);
  }

  @Override
  public String toString() {
    return "VisualImage[redacted]";
  }
}
