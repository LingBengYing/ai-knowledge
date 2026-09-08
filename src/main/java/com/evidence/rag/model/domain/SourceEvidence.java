package com.evidence.rag.model.domain;

/** Exact saved citation range; callers must not substitute the whole source page. */
public record SourceEvidence(PublishedEvidence evidence, int start, int end) {
  public SourceEvidence {
    if (evidence == null
        || start < evidence.segment().start()
        || end > evidence.segment().end()
        || start >= end) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "SourceEvidence[redacted]";
  }
}
