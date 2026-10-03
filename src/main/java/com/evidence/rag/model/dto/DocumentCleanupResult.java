package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Safe cleanup state, separate from withdrawal acceptance. */
public record DocumentCleanupResult(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("cleanup_id") String cleanupId,
    String status,
    @JsonProperty("cleanup_status") String cleanupStatus,
    @JsonProperty("requested_at") String requestedAt,
    @JsonProperty("updated_at") String updatedAt,
    @JsonProperty("completed_at") String completedAt,
    @JsonProperty("error_code") String errorCode,
    List<DocumentCleanupResourceResult> resources) {
  public DocumentCleanupResult {
    resources = List.copyOf(resources);
  }

  @Override
  public String toString() {
    return "DocumentCleanupResult[redacted]";
  }
}
