package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One saved citation rebuilt under the original actor's current complete authority scope. */
public record VideoSourceResult(
    @JsonProperty("answer_id") String answerId, VideoCitationResult citation) {
  @Override
  public String toString() {
    return "VideoSourceResult[redacted]";
  }
}
