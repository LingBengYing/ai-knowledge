package com.evidence.rag.model.dto;

import java.util.Objects;

/** Display-only model increments. Neither a tool invocation nor verified answer evidence. */
public record AgentModelDelta(String reasoningDelta, String answerDelta) {
  public AgentModelDelta {
    Objects.requireNonNull(reasoningDelta);
    Objects.requireNonNull(answerDelta);
  }

  @Override
  public String toString() {
    return "AgentModelDelta[redacted]";
  }
}
