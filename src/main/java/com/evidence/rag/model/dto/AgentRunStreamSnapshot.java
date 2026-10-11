package com.evidence.rag.model.dto;

import java.util.List;

/** Cumulative streaming view. Preview text is provisional and never citation authority. */
public record AgentRunStreamSnapshot(long revision, RunView run) {
  public AgentRunStreamSnapshot(long revision, AgentRunResult result, String preview) {
    this(
        revision,
        new RunView(
            result.id(),
            result.status(),
            result.events(),
            result.result(),
            result.suggestions(),
            result.error(),
            preview));
  }

  public record RunView(
      String id,
      String status,
      List<AgentRunResult.Event> events,
      KnowledgeAnswerResult result,
      List<AgentProtocol.Suggestion> suggestions,
      AgentRunResult.Problem error,
      String preview) {
    public RunView {
      events = List.copyOf(events);
      suggestions = List.copyOf(suggestions);
      preview = preview == null ? "" : preview;
    }

    @Override
    public String toString() {
      return "AgentRunStreamView[redacted]";
    }
  }

  @Override
  public String toString() {
    return "AgentRunStreamSnapshot[redacted]";
  }
}
