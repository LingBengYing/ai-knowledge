package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.AgentFailureCode;
import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Event(int sequence, String type, String message, Details details) {
    public Event(int sequence, String type, String message) {
      this(sequence, type, message, null);
    }

    @Override
    public String toString() {
      return "AgentEvent[redacted]";
    }
  }

  /** Public execution metadata, separate from native model history and evidence content. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Details(
      String status,
      @JsonProperty("tool_name") String toolName,
      String query,
      @JsonProperty("elapsed_ms") Long elapsedMs,
      @JsonProperty("result_count") Integer resultCount,
      @JsonProperty("document_count") Integer documentCount,
      List<Source> sources,
      @JsonProperty("safe_code") String safeCode,
      String reasoning,
      @JsonProperty("reasoning_truncated") boolean reasoningTruncated,
      @JsonProperty("tool_names") List<String> toolNames) {
    public Details {
      sources = sources == null ? List.of() : List.copyOf(sources);
      toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
      if (status == null
          || !Set.of("running", "completed", "failed", "cancelled").contains(status)
          || toolName == null
          || !Set.of("model", "knowledge_search", "knowledge_read").contains(toolName)
          || (query != null && (query.isBlank() || query.length() > 4000))
          || (elapsedMs != null && elapsedMs < 0)
          || (resultCount != null && resultCount < 0)
          || (documentCount != null && documentCount < 0)
          || sources.size() > 32
          || (safeCode != null
              && !safeCode.equals(AgentFailureCode.safe(safeCode, AgentFailureCode.AGENT_FAILED)))
          || (reasoning != null
              && (reasoning.isBlank()
                  || reasoning.codePointCount(0, reasoning.length()) > 16000
                  || reasoning.indexOf(0) >= 0))
          || (reasoningTruncated && reasoning == null)
          || toolNames.size() > 16
          || toolNames.stream()
              .anyMatch(
                  name ->
                      !Set.of("knowledge_search", "knowledge_read", "terminate").contains(name))) {
        throw ModelValues.invalid();
      }
    }

    public static Details running(String toolName, String query) {
      return new Details(
          "running", toolName, query, null, null, null, List.of(), null, null, false, List.of());
    }

    @Override
    public String toString() {
      return "AgentEventDetails[redacted]";
    }
  }

  /** Server-owned source coordinates; no raw tool text or model-provided links. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(
      @JsonProperty("source_id") String sourceId,
      @JsonProperty("document_id") String documentId,
      @JsonProperty("revision_id") String revisionId,
      String title,
      String kind,
      Integer page,
      @JsonProperty("start_ms") BigDecimal startMs,
      @JsonProperty("end_ms") BigDecimal endMs) {
    public Source {
      ModelValues.identifier(sourceId, 128);
      ModelValues.identifier(documentId, 100);
      ModelValues.indexIdentity(revisionId);
      ModelValues.identifier(title, 255);
      if (kind == null
          || !Set.of("document_text", "video_transcript", "video_subtitle", "video_frame_ocr")
              .contains(kind)
          || (page != null && page < 1)
          || (startMs != null && startMs.signum() < 0)
          || (endMs != null && (startMs == null || endMs.compareTo(startMs) <= 0))) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "AgentEventSource[redacted]";
    }
  }

  public record Problem(String code, String message) {}

  @Override
  public String toString() {
    return "AgentRunResult[redacted]";
  }
}
