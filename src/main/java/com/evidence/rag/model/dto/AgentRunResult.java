package com.evidence.rag.model.dto;

import java.util.List;

/** Safe process-local task view. Formal answers use the existing durable knowledge trace. */
public record AgentRunResult(
    String id,
    String status,
    List<Event> events,
    KnowledgeAnswerResult result,
    List<AgentProtocol.Suggestion> suggestions,
    Problem error) {
  public AgentRunResult {
    events = List.copyOf(events);
    suggestions = List.copyOf(suggestions);
  }

  public record Event(int sequence, String type, String message) {}

  public record Problem(String code, String message) {}

  @Override
  public String toString() {
    return "AgentRunResult[redacted]";
  }
}
