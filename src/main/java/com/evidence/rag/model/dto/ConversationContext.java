package com.evidence.rag.model.dto;

import java.util.List;

/** Non-evidence dialogue context: checkpoints never confer authority on prior source IDs. */
public record ConversationContext(String summary, List<AgentMessage> history) {
  public ConversationContext {
    history = List.copyOf(history);
  }

  public static ConversationContext empty() {
    return new ConversationContext("", List.of());
  }

  @Override
  public String toString() {
    return "ConversationContext[redacted]";
  }
}
