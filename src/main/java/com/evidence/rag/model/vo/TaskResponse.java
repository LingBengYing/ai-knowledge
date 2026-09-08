package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public task variants; no worker credentials, raw source or implementation-only flag. */
public sealed interface TaskResponse permits TaskResponse.Ingestion, TaskResponse.Indexing {
  record Ingestion(
      @JsonProperty("task_id") String taskId,
      @JsonProperty("document_id") String documentId,
      @JsonProperty("revision_id") String revisionId,
      @JsonProperty("filename") String filename,
      @JsonProperty("state") String state,
      @JsonProperty("status") String status,
      @JsonProperty("attempt") int attempt,
      @JsonProperty("error_code") String errorCode,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("updated_at") String updatedAt,
      @JsonProperty("can_retry") boolean canRetry,
      @JsonProperty("can_cancel") boolean canCancel)
      implements TaskResponse {}

  record Indexing(
      @JsonProperty("task_id") String taskId,
      @JsonProperty("document_id") String documentId,
      @JsonProperty("revision_id") String revisionId,
      @JsonProperty("filename") String filename,
      @JsonProperty("state") String state,
      @JsonProperty("status") String status,
      @JsonProperty("attempt") int attempt,
      @JsonProperty("error_code") String errorCode,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("updated_at") String updatedAt,
      @JsonProperty("can_retry") boolean canRetry,
      @JsonProperty("can_cancel") boolean canCancel,
      @JsonProperty("index_publication_id") String indexPublicationId)
      implements TaskResponse {}
}
