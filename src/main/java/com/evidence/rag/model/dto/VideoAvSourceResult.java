package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvSourceResult(
    @JsonProperty("answer_id") String answerId, VideoAvCitationResult citation) {
  @Override
  public String toString() {
    return "VideoAvSourceResult[redacted]";
  }
}
