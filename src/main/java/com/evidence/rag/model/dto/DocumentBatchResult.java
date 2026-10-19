package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One page of a batch. {@code matched} is how many files the optional state filter selects (the
 * total when unfiltered); {@code offset} and {@code limit} page through those. {@code throughput}
 * counts the files that finished within the recent window, for a rate and an estimate. {@code open}
 * is true while an open batch's manifest is still growing ({@code expectedTotal} is the announced
 * file count of a batch registered open, null for a one-shot manifest).
 */
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
    List<File> items,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    @JsonProperty("state_filter") List<String> stateFilter,
    int matched,
    Throughput throughput,
    boolean open,
    @JsonProperty("expected_total") Integer expectedTotal) {
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
      @JsonProperty("error_code") String errorCode,
      @JsonProperty("duplicate_of_document_id") String duplicateOfDocumentId,
      @JsonProperty("duplicate_of_ordinal") Integer duplicateOfOrdinal,
      @JsonProperty("relative_path") String relativePath,
      @JsonProperty("started_at") String startedAt,
      @JsonProperty("finished_at") String finishedAt) {}

  /**
   * Files that reached indexed, failed or cancelled within the last {@code windowSeconds} (the
   * window is cut to the batch's age), measured at {@code measuredAt}.
   */
  public record Throughput(
      @JsonProperty("window_seconds") long windowSeconds,
      long finished,
      @JsonProperty("measured_at") String measuredAt) {}
}
