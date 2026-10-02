package com.evidence.rag.model.domain;

/** Hash-only attachment preparation outcome; never a library citation. */
public record QueryTraceAttachment(
    int ordinal,
    String sourceSha256,
    QueryAttachment.Kind mediaKind,
    QueryAttachmentManifest manifest) {
  public QueryTraceAttachment {
    if (ordinal < 0
        || ordinal >= 3
        || sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || mediaKind == null
        || (manifest != null
            && (manifest.ordinal() != ordinal
                || !manifest.sourceSha256().equals(sourceSha256)
                || manifest.mediaKind() != mediaKind))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "QueryTraceAttachment[redacted]";
  }
}
