package com.evidence.rag.model.domain;

public record VideoAvManagedEvidence(
    String sourceRevisionId, String publicationId, int windowCount) {
  public VideoAvManagedEvidence {
    ModelValues.indexIdentity(sourceRevisionId);
    if (publicationId == null ? windowCount != 0 : windowCount < 1 || windowCount > 1201) {
      throw ModelValues.invalid();
    }
    if (publicationId != null) {
      ModelValues.indexIdentity(publicationId);
    }
  }
}
