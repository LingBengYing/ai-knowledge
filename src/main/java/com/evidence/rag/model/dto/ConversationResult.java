package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Durable public dialogue only; private reasoning and tool payloads are not part of this DTO. */
public record ConversationResult(
    String id,
    String title,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt,
    @JsonProperty("turn_count") long turnCount,
    @JsonProperty("active_run_id") String activeRunId,
    Compression compression) {
  public record Compression(
      String status,
      @JsonProperty("covered_turn_count") long coveredTurnCount,
      @JsonProperty("summary_characters") int summaryCharacters) {}

  public record Turn(
      long ordinal,
      @JsonProperty("run_id") String runId,
      String question,
      String status,
      AgentRunResult result,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("updated_at") long updatedAt) {
    @Override
    public String toString() {
      return "ConversationTurn[redacted]";
    }
  }

  public record Detail(
      ConversationResult conversation,
      List<Turn> turns,
      int offset,
      int limit,
      @JsonProperty("has_more") boolean hasMore) {
    public Detail {
      turns = List.copyOf(turns);
    }

    @Override
    public String toString() {
      return "ConversationDetail[redacted]";
    }
  }

  public record Page(List<ConversationResult> items, int offset, int limit) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
