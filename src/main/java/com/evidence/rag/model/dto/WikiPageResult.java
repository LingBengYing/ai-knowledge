package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An immutable reviewed version plus the current availability of its original sources. */
public record WikiPageResult(
    @JsonProperty("page_id") String pageId,
    long version,
    WikiContentResult content,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("policy_revision") String policyRevision,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("source_state") String sourceState,
    String state,
    @JsonProperty("lifecycle_version") long lifecycleVersion) {
  @Override
  public String toString() {
    return "WikiPageResult[redacted]";
  }
}
