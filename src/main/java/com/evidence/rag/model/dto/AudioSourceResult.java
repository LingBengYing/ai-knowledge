package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Current-authorized audio citation reconstructed from a saved answer trace. */
public record AudioSourceResult(
    @JsonProperty("answer_id") String answerId, AudioCitationResult citation) {
  @Override
  public String toString() {
    return "AudioSourceResult[redacted]";
  }
}
