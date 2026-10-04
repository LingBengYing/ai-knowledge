package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record KnowledgeAnswerResult(
    @JsonProperty("answer_id") String answerId, String status, String answer, String reason,
    List<KnowledgeCitation> citations) {
  public KnowledgeAnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "KnowledgeAnswerResult[redacted]";
  }
}
