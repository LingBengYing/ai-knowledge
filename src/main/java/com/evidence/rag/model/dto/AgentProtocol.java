package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Restricted DTOs for the optional DB-GPT service; no credentials in diagnostics. */
public final class AgentProtocol {
  private AgentProtocol() {}

  public record RunRequest(
      @JsonProperty("run_id") String runId,
      String question,
      @JsonProperty("callback_token") String callbackToken) {
    @Override
    public String toString() {
      return "AgentRunRequest[redacted]";
    }
  }

  public record Proposal(
      boolean refused, List<Statement> statements, List<Suggestion> suggestions) {
    public Proposal {
      statements = List.copyOf(statements);
      suggestions = List.copyOf(suggestions);
    }

    @Override
    public String toString() {
      return "AgentProposal[redacted]";
    }
  }

  public record Statement(String text, @JsonProperty("evidence_ids") List<String> evidenceIds) {
    public Statement {
      evidenceIds = List.copyOf(evidenceIds);
    }

    @Override
    public String toString() {
      return "AgentStatement[redacted]";
    }
  }

  public record Suggestion(
      String title, String reason, @JsonProperty("document_ids") List<String> documentIds) {
    public Suggestion {
      documentIds = List.copyOf(documentIds);
    }

    @Override
    public String toString() {
      return "AgentSuggestion[redacted]";
    }
  }

  public record ModelRequest(List<AgentMessage> messages) {
    public ModelRequest {
      if (messages == null
          || messages.isEmpty()
          || messages.size() > 64
          || messages.stream().anyMatch(java.util.Objects::isNull)
          || messages.stream().mapToLong(m -> m.content().length()).sum() > 262144)
        throw ModelValues.invalid();
      messages = List.copyOf(messages);
    }

    @Override
    public String toString() {
      return "AgentModelRequest[redacted]";
    }
  }

  public record ModelResult(String content) {
    @Override
    public String toString() {
      return "AgentModelResult[redacted]";
    }
  }

  public record SearchRequest(String query) {}

  public record ReadRequest(@JsonProperty("source_ids") List<String> sourceIds) {
    public ReadRequest {
      if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > 32)
        throw ModelValues.invalid();
      sourceIds = List.copyOf(sourceIds);
    }
  }

  public record SearchSource(
      @JsonProperty("source_id") String sourceId,
      String title,
      String kind,
      String excerpt,
      @JsonProperty("document_id") String documentId) {
    @Override
    public String toString() {
      return "AgentSearchSource[redacted]";
    }
  }

  public record ReadSource(
      @JsonProperty("source_id") String sourceId,
      String title,
      String kind,
      String text,
      @JsonProperty("document_id") String documentId) {
    @Override
    public String toString() {
      return "AgentReadSource[redacted]";
    }
  }

  public record SearchResult(List<SearchSource> sources) {
    public SearchResult {
      sources = List.copyOf(sources);
    }
  }

  public record ReadResult(List<ReadSource> sources) {
    public ReadResult {
      sources = List.copyOf(sources);
    }
  }
}
