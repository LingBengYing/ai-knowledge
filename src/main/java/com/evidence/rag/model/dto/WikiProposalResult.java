package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Public before/after review state; source identity is always server assigned. */
public record WikiProposalResult(
    String id,
    @JsonProperty("page_id") String pageId,
    @JsonProperty("base_version") long baseVersion,
    WikiContentResult before,
    WikiContentResult after,
    @JsonProperty("generation_method") String generationMethod,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("policy_revision") String policyRevision,
    String status,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("reviewed_at") Long reviewedAt,
    @JsonProperty("source_state") String sourceState) {
  @Override
  public String toString() {
    return "WikiProposalResult[redacted]";
  }
}
