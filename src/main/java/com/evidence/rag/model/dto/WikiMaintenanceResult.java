package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Read-only, rule-based maintenance observations; not automatic repair or semantic assessment. */
public record WikiMaintenanceResult(
    List<Item> items, long total, int offset, int limit, Map<String, Long> counts) {
  public WikiMaintenanceResult {
    items = List.copyOf(items);
    counts = Map.copyOf(counts);
  }

  public record Item(
      String id,
      String kind,
      @JsonProperty("target_type") String targetType,
      @JsonProperty("target_id") String targetId,
      String title,
      @JsonProperty("revision_id") String revisionId,
      @JsonProperty("error_code") String errorCode,
      @JsonProperty("related_document_id") String relatedDocumentId) {}
}
