package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Public answer shape: constructed only after authoritative trace commit. */
public record AnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String answer,
    String reason,
    List<CitationResult> citations)
    implements AnswerPayload {
  public AnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "AnswerResult[redacted]";
  }
}
