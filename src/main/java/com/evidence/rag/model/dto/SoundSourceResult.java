package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundSourceResult(
    @JsonProperty("answer_id") String answerId, SoundCitationResult citation) {
  @Override
  public String toString() {
    return "SoundSourceResult[redacted]";
  }
}
