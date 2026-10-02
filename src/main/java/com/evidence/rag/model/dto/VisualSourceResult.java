package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VisualSourceResult(
    @JsonProperty("answer_id") String answerId, VisualCitationResult citation) {
  @Override
  public String toString() {
    return "VisualSourceResult[redacted]";
  }
}
