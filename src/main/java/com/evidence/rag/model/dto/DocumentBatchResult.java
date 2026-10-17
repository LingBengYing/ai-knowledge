package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record DocumentBatchResult(
    @JsonProperty("batch_id") String batchId,
    String state,
    int total,
    int offset,
    int limit,
    Map<String, Long> counts,
    @JsonProperty("embedding_request_limit") long requestLimit,
    @JsonProperty("reserved_requests") long reservedRequests,
    @JsonProperty("observed_requests") long observedRequests,
    @JsonProperty("usage_uncertain") boolean usageUncertain,
    @JsonProperty("halted_reason") String haltedReason,
    @JsonProperty("retryable_files") long retryableFiles,
    @JsonProperty("available_actions") List<String> availableActions,
    List<File> items) {
  public record File(
      int ordinal,
      String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("source_sha256") String sourceSha256,
      String state,
      @JsonProperty("document_id") String documentId,
      @JsonProperty("revision_id") String revisionId,
      @JsonProperty("ingestion_task_id") String ingestionTaskId,
      @JsonProperty("indexing_task_id") String indexingTaskId,
      @JsonProperty("error_code") String errorCode) {}
}
