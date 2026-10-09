package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Shared typed source locators, without original file bytes or internal model/index settings. */
public record WikiSourceResponse(
    @JsonProperty("source_id") String sourceId,
    @JsonProperty("evidence_id") String evidenceId,
    String kind,
    String sha256,
    String filename,
    @JsonProperty("media_type") String mediaType,
    String text,
    @JsonProperty("proof_origin") String proofOrigin,
    @JsonProperty("time_precision") String timePrecision,
    SynopsisSourceResponse.Locator locator,
    @JsonProperty("content_url") String contentUrl,
    @JsonProperty("frame_url") String frameUrl) {
  @Override
  public String toString() {
    return "WikiSourceResponse[redacted]";
  }
}
