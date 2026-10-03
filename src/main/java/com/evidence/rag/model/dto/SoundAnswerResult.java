package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundAnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String answer,
    @JsonProperty("reason_code") String reasonCode,
    List<SoundCitationResult> citations,
    @JsonProperty("policy_revision") String policyRevision) {
  public SoundAnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "SoundAnswerResult[redacted]";
  }
}
