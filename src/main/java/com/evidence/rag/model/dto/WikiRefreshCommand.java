package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import java.util.List;
import java.util.Set;

public record WikiRefreshCommand(
    long baseVersion, String generationMethod, List<WikiSourceVersion> sourceVersions) {
  public WikiRefreshCommand {
    if (baseVersion < 1
        || baseVersion >= 9_007_199_254_740_991L
        || generationMethod == null
        || !Set.of("model", "extractive").contains(generationMethod)
        || sourceVersions == null
        || sourceVersions.isEmpty()
        || sourceVersions.size() > 50
        || sourceVersions.stream().anyMatch(value -> value == null)
        || sourceVersions.stream().map(WikiSourceVersion::documentId).distinct().count()
            != sourceVersions.size()) {
      throw ModelValues.invalid();
    }
    sourceVersions = List.copyOf(sourceVersions);
  }
}
