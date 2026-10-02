package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Hash-only request preparation record; contains no attachment text, filename or bytes. */
public record QueryAttachmentManifest(
    int ordinal,
    String sourceSha256,
    QueryAttachment.Kind mediaKind,
    String compilerRevision,
    String contentSha256,
    int textCodePoints,
    int visualCount,
    List<String> selectedImageSha256,
    boolean visualSampled) {
  public QueryAttachmentManifest {
    if (ordinal < 0
        || ordinal >= 3
        || sourceSha256 == null
        || !sourceSha256.matches("[0-9a-f]{64}")
        || contentSha256 == null
        || !contentSha256.matches("[0-9a-f]{64}")
        || mediaKind == null
        || textCodePoints < 0
        || textCodePoints > 8192
        || visualCount < 0
        || visualCount > 128
        || selectedImageSha256 == null
        || selectedImageSha256.size() > 3
        || selectedImageSha256.size() > visualCount
        || (mediaKind == QueryAttachment.Kind.IMAGE && visualCount != 1)
        || (mediaKind == QueryAttachment.Kind.AUDIO && visualCount != 0)
        || (mediaKind == QueryAttachment.Kind.VIDEO && visualCount == 0)
        || (visualSampled && selectedImageSha256.size() == visualCount)
        || (!visualSampled && visualCount > 0 && selectedImageSha256.isEmpty())) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(compilerRevision, 200);
    var unique = new HashSet<String>();
    for (String sha : selectedImageSha256) {
      if (sha == null || !sha.matches("[0-9a-f]{64}") || !unique.add(sha)) {
        throw ModelValues.invalid();
      }
    }
    selectedImageSha256 = List.copyOf(selectedImageSha256);
  }

  @Override
  public String toString() {
    return "QueryAttachmentManifest[redacted]";
  }
}
