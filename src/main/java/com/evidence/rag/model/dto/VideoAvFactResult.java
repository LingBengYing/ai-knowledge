package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvFactResult(
    String id,
    String text,
    String requirement,
    @JsonProperty("visual_contribution") boolean visualContribution,
    @JsonProperty("audio_contribution") boolean audioContribution) {
  @Override
  public String toString() {
    return "VideoAvFactResult[redacted]";
  }
}
