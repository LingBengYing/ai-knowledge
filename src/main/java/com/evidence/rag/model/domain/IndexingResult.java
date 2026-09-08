package com.evidence.rag.model.domain;

import java.util.Map;

/** Complete digest manifest and the internal Adapter's full-revision verification receipt. */
public record IndexingResult(Map<String, String> entryDigests, VerifiedRevision verified) {
  public IndexingResult {
    if (entryDigests == null || verified == null) {
      throw new IllegalArgumentException("Invalid indexing result");
    }
    entryDigests = Map.copyOf(entryDigests);
  }

  @Override
  public String toString() {
    return "IndexingResult[redacted]";
  }
}
