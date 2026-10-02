package com.evidence.rag.model.domain;

/** Authorized original bytes; a saved source additionally carries its immutable trace metadata. */
public record VisualSourceEvidence(
    PublishedImageEvidence source, VisualImage original, VisualTraceEvidence trace) {
  public VisualSourceEvidence {
    if (source == null
        || original == null
        || !source.publication().sourceSha256().equals(original.sha256())
        || !source.mediaType().equals(original.mediaType())
        || trace != null && !source.physicalSegmentId().equals(trace.physicalSegmentId())) {
      throw ModelValues.invalid();
    }
  }

  public VisualSourceEvidence(PublishedImageEvidence source, VisualImage original) {
    this(source, original, null);
  }

  @Override
  public String toString() {
    return "VisualSourceEvidence[redacted]";
  }
}
