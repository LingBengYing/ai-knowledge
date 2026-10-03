package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentCleanupBatchItemResult(
    @JsonProperty("document_id") String documentId,
    String status,
    DocumentCleanupResult cleanup,
    @JsonProperty("error_code") String errorCode) {
  @Override
  public String toString() {
    return "DocumentCleanupBatchItemResult[redacted]";
  }
}
