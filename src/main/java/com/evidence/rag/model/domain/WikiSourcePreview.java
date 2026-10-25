package com.evidence.rag.model.domain;

/** Bounded text-only preview; no original binary or projection configuration is exposed. */
public record WikiSourcePreview(long characters, long parts, String excerpt, boolean truncated) {
  @Override
  public String toString() {
    return "WikiSourcePreview[redacted]";
  }
}
