package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native Chat Completions history. Tool results retain the provider's exact call identity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage(
    String role,
    String content,
    @JsonProperty("tool_calls") List<ToolCall> toolCalls,
    @JsonProperty("tool_call_id") String toolCallId,
    @JsonProperty("reasoning_content") String reasoningContent) {
  public AgentMessage {
    if (role == null || !Set.of("system", "user", "assistant", "tool").contains(role)) {
      throw ModelValues.invalid();
    }
    if ("assistant".equals(role) && toolCalls != null) {
      if (content != null
          || toolCallId != null
          || toolCalls.isEmpty()
          || toolCalls.size() > 16
          || toolCalls.stream().anyMatch(java.util.Objects::isNull)) throw ModelValues.invalid();
      toolCalls = List.copyOf(toolCalls);
      if (reasoningContent != null
          && (reasoningContent.length() > 262144 || reasoningContent.indexOf(0) >= 0)) {
        throw ModelValues.invalid();
      }
      var ids = new HashSet<String>();
      for (var call : toolCalls) {
        if (!ids.add(call.id())
            || ("terminate".equals(call.function().name()) && toolCalls.size() != 1)) {
          throw ModelValues.invalid();
        }
      }
    } else {
      text(content);
      if (toolCalls != null || reasoningContent != null) throw ModelValues.invalid();
      if ("tool".equals(role)) callId(toolCallId);
      else if (toolCallId != null) throw ModelValues.invalid();
    }
  }

  public AgentMessage(String role, String content) {
    this(role, content, null, null, null);
  }

  public AgentMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    this(role, content, toolCalls, toolCallId, null);
  }

  public record ToolCall(String id, String type, FunctionCall function) {
    public ToolCall {
      callId(id);
      if (!"function".equals(type) || function == null) throw ModelValues.invalid();
    }

    @Override
    public String toString() {
      return "AgentToolCall[redacted]";
    }
  }

  public record FunctionCall(String name, String arguments) {
    public FunctionCall {
      if (name == null
          || !Set.of("knowledge_search", "knowledge_read", "terminate").contains(name)) {
        throw ModelValues.invalid();
      }
      text(arguments);
    }

    @Override
    public String toString() {
      return "AgentFunctionCall[redacted]";
    }
  }

  long contentSize() {
    long size = content == null ? 0 : content.length();
    if (toolCallId != null) size += toolCallId.length();
    if (reasoningContent != null) size += reasoningContent.length();
    if (toolCalls != null) {
      for (var call : toolCalls) {
        size +=
            call.id().length()
                + call.function().name().length()
                + call.function().arguments().length();
      }
    }
    return size;
  }

  private static void text(String value) {
    if (value == null || value.isBlank() || value.length() > 262144 || value.indexOf(0) >= 0) {
      throw ModelValues.invalid();
    }
  }

  private static void callId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}")) throw ModelValues.invalid();
  }

  @Override
  public String toString() {
    return "AgentMessage[redacted]";
  }
}
