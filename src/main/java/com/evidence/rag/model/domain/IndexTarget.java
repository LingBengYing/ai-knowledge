package com.evidence.rag.model.domain;

import static com.evidence.rag.model.domain.ModelValues.indexIdentity;
import static com.evidence.rag.model.domain.ModelValues.invalid;

import java.util.Locale;
import java.util.Set;

public record IndexTarget(
    String embeddingIdentity, String projectionIdentity, String modelRevision, int dimensions) {
  public IndexTarget {
    indexIdentity(embeddingIdentity);
    indexIdentity(projectionIdentity);
    if (modelRevision == null
        || !modelRevision.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
        || Set.of("latest", "default", "unknown").contains(modelRevision.toLowerCase(Locale.ROOT))
        || dimensions < 2
        || dimensions > 8192) {
      throw invalid();
    }
  }

  @Override
  public String toString() {
    return "IndexTarget[redacted]";
  }
}
