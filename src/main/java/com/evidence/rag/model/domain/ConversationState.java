package com.evidence.rag.model.domain;

/** Stored public dialogue and derived checkpoint; never retrieval evidence. */
public record ConversationState(
    String id,
    String title,
    long createdAt,
    long updatedAt,
    long turnCount,
    String activeRunId,
    long generation,
    String summary,
    long coveredTurnCount,
    String compressionStatus) {
  @Override
  public String toString() {
    return "ConversationState[redacted]";
  }

  public record Turn(
      long ordinal,
      String runId,
      String question,
      String status,
      String resultJson,
      long createdAt,
      long updatedAt) {
    @Override
    public String toString() {
      return "ConversationTurn[redacted]";
    }
  }
}
