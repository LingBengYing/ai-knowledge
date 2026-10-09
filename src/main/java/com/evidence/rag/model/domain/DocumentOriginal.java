package com.evidence.rag.model.domain;

import java.util.Map;
import java.util.Set;

/** Immutable saved upload, validated while its current authority transaction is still held. */
public record DocumentOriginal(
    String documentId,
    String revisionId,
    String filename,
    String documentType,
    String mediaType,
    String sourceSha256,
    long sizeBytes,
    byte[] content) {
  private static final Map<String, Set<String>> MEDIA_TYPES =
      Map.of(
          "document", DocumentFormat.mediaTypes(),
          "image", Set.of("image/png", "image/jpeg"),
          "audio",
              Set.of(
                  "audio/wav", "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/webm"),
          "video", Set.of("video/mp4", "video/quicktime", "video/webm", "video/x-matroska"));

  public DocumentOriginal {
    if (documentId == null
        || revisionId == null
        || filename == null
        || documentType == null
        || mediaType == null
        || !MEDIA_TYPES.getOrDefault(documentType, Set.of()).contains(mediaType)
        || sourceSha256 == null
        || content == null
        || content.length < 1
        || content.length > 20 * 1024 * 1024
        || sizeBytes != content.length
        || !ModelValues.sha256(content).equals(sourceSha256)) {
      throw ModelValues.notFound();
    }
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public String toString() {
    return "DocumentOriginal[redacted]";
  }
}
