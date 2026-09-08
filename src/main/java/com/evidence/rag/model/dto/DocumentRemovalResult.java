package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A committed withdrawal request, never a receipt for physical cleanup. */
public record DocumentRemovalResult(
    @JsonProperty("document_id") String documentId,
    String status,
    @JsonProperty("cleanup_status") String cleanupStatus,
    @JsonProperty("requested_at") String requestedAt) {
  @Override
  public String toString() {
    return "DocumentRemovalResult[receipt=redacted]";
  }
}
