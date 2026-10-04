package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Public progress contains no model credentials, document contents or projection addresses. */
public record ModelRebuildResult(
    @JsonProperty("target_version") long targetVersion,
    @JsonProperty("active_version") Long activeVersion,
    boolean required,
    @JsonProperty("can_start") boolean canStart,
    String reason,
    @JsonProperty("total_documents") int totalDocuments,
    Job job) {
  public record Job(
      String id,
      @JsonProperty("base_active_version") Long baseActiveVersion,
      @JsonProperty("target_version") long targetVersion,
      String state,
      @JsonProperty("total_documents") int totalDocuments,
      @JsonProperty("completed_documents") int completedDocuments,
      @JsonProperty("error_code") String errorCode,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("updated_at") String updatedAt) {}
}
