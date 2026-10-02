package com.evidence.rag.model.domain;

/** Request-lifetime original media, never an ingested or published library source. */
public record QueryAttachment(String filename, String mediaType, byte[] content) {
  public QueryAttachment {
    ModelValues.identifier(filename, 255);
    if (filename.isBlank()
        || filename.contains("/")
        || filename.contains("\\")
        || content == null
        || content.length == 0) {
      throw ModelValues.invalid();
    }
    mediaKind(mediaType);
    content = content.clone();
  }

  public enum Kind {
    IMAGE,
    AUDIO,
    VIDEO
  }

  public Kind kind() {
    return mediaKind(mediaType);
  }

  public String sha256() {
    return ModelValues.sha256(content);
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  private static Kind mediaKind(String mediaType) {
    if (mediaType == null) {
      throw ModelValues.invalid();
    }
    return switch (mediaType) {
      case "image/png", "image/jpeg" -> Kind.IMAGE;
      case "audio/wav", "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/webm" ->
          Kind.AUDIO;
      case "video/mp4", "video/quicktime", "video/webm", "video/x-matroska" -> Kind.VIDEO;
      default -> throw ModelValues.invalid();
    };
  }

  @Override
  public String toString() {
    return "QueryAttachment[redacted]";
  }
}
