package com.evidence.rag.model.domain;

import java.util.List;
import java.util.Map;

/** Observations computed from authority metadata in one read transaction. */
public record WikiMaintenanceSnapshot(List<Observation> observations, Map<String, Long> counts) {
  public WikiMaintenanceSnapshot {
    observations = List.copyOf(observations);
    counts = Map.copyOf(counts);
  }

  public record Observation(
      String kind,
      String targetType,
      String targetId,
      String title,
      String revisionId,
      String errorCode,
      String relatedDocumentId) {}
}
