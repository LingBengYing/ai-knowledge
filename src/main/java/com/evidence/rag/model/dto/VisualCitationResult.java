package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Whole-image source locator, deliberately without text page/offset/quote fields. */
public record VisualCitationResult(
    int number,
    String kind,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    String filename,
    @JsonProperty("media_type") String mediaType,
    int width,
    int height,
    List<Double> bbox,
    @JsonProperty("coordinate_system") String coordinateSystem,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("policy_revision") String policyRevision,
    @JsonProperty("source_url") String sourceUrl,
    @JsonProperty("content_url") String contentUrl) {
  public VisualCitationResult {
    bbox = List.copyOf(bbox);
  }

  @Override
  public String toString() {
    return "VisualCitationResult[redacted]";
  }
}
