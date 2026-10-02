package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VisualAnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String answer,
    String reason,
    List<VisualCitationResult> citations)
    implements AnswerPayload {
  public VisualAnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "VisualAnswerResult[redacted]";
  }
}
