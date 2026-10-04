package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KnowledgeSourceResult(
    @JsonProperty("answer_id") String answerId, KnowledgeCitation citation) {
  @Override
  public String toString() {
    return "KnowledgeSourceResult[redacted]";
  }
}
