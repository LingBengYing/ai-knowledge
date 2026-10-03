package com.evidence.rag.model.domain;

/** Metadata for genuinely registered sound originals, including not-yet-indexed uploads. */
public record SoundManagedEvidence(String sourceRevisionId, String publicationId, int spanCount) {
  public SoundManagedEvidence {
    ModelValues.indexIdentity(sourceRevisionId);
    if (spanCount < 0 || spanCount > 600 || (publicationId == null) != (spanCount == 0)) {
      throw ModelValues.invalid();
    }
    if (publicationId != null) {
      ModelValues.indexIdentity(publicationId);
    }
  }
}
