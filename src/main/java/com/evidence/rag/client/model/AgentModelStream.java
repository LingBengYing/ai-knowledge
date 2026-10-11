package com.evidence.rag.client.model;

import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentModelDelta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Aggregates native OpenAI deltas; only a fully validated turn can become execution authority. */
final class AgentModelStream {
  private final Consumer<AgentModelDelta> listener;
  private final StringBuilder reasoning = new StringBuilder();
  private final StringBuilder content = new StringBuilder();
  private final List<Call> calls = new ArrayList<>();
  private String role;
  private String finishReason;
  private boolean done;
  private char reasoningHighSurrogate;

  AgentModelStream(Consumer<AgentModelDelta> listener) {
    this.listener = java.util.Objects.requireNonNull(listener);
  }

  void accept(String event) {
    if (done) {
      throw invalid();
    }
    if (event.equals("[DONE]")) {
      if (finishReason == null) {
        throw invalid();
      }
      done = true;
      return;
    }
    var response = ModelHttpTransport.parseObject(event);
    if (response.hasNonNull("error")) {
      throw invalid();
    }
    var choices = response.path("choices");
    // The optional usage-only frame arrives after the finish reason, before [DONE].
    if (choices.isArray() && choices.isEmpty() && response.hasNonNull("usage")) {
      return;
    }
    if (!choices.isArray() || choices.size() != 1 || finishReason != null) {
      throw invalid();
    }
    var choice = choices.get(0);
    if (!choice.path("index").isIntegralNumber()
        || !choice.path("index").canConvertToInt()
        || choice.path("index").intValue() != 0) {
      throw invalid();
    }
    var delta = choice.path("delta");
    if (!delta.isObject() || delta.hasNonNull("function_call") || delta.hasNonNull("refusal")) {
      throw invalid();
    }
    if (delta.hasNonNull("role")) {
      String next = string(delta.path("role"));
      if (!"assistant".equals(next) || role != null && !role.equals(next)) {
        throw invalid();
      }
      role = next;
    }
    if (delta.hasNonNull("content")) {
      append(content, string(delta.path("content")), 262144);
    }
    if (delta.hasNonNull("reasoning_content")) {
      String part = string(delta.path("reasoning_content"));
      append(reasoning, part, 262144);
      String display = reasoningDisplay(part);
      if (!display.isEmpty()) {
        listener.accept(new AgentModelDelta(display, ""));
      }
    }
    if (delta.hasNonNull("tool_calls")) {
      var values = delta.path("tool_calls");
      if (!values.isArray() || values.size() > 16) {
        throw invalid();
      }
      for (var value : values) {
        var index = value.path("index");
        if (!value.isObject()
            || !index.isIntegralNumber()
            || !index.canConvertToInt()
            || index.intValue() < 0
            || index.intValue() > calls.size()
            || index.intValue() >= 16) {
          throw invalid();
        }
        if (index.intValue() == calls.size()) {
          calls.add(new Call());
        }
        var call = calls.get(index.intValue());
        for (String field : value.propertyNames()) {
          if (!List.of("index", "id", "type", "function").contains(field)) {
            throw invalid();
          }
        }
        if (value.hasNonNull("id")) {
          append(call.id, string(value.path("id")), 256);
        }
        if (value.hasNonNull("type")) {
          String type = string(value.path("type"));
          if (!"function".equals(type) || call.type != null && !call.type.equals(type)) {
            throw invalid();
          }
          call.type = type;
        }
        if (value.hasNonNull("function")) {
          var function = value.path("function");
          if (!function.isObject()
              || function.propertyNames().stream()
                  .anyMatch(k -> !List.of("name", "arguments").contains(k))) {
            throw invalid();
          }
          if (function.hasNonNull("name")) {
            append(call.name, string(function.path("name")), 128);
          }
          String arguments =
              function.hasNonNull("arguments") ? string(function.path("arguments")) : "";
          append(call.arguments, arguments, 262144);
          if ("terminate".contentEquals(call.name) && calls.size() == 1) {
            if (call.preview == null) {
              call.preview =
                  new AgentAnswerPreview(text -> listener.accept(new AgentModelDelta("", text)));
              call.preview.accept(call.arguments.toString());
            } else {
              call.preview.accept(arguments);
            }
          }
        }
      }
    }
    if (choice.hasNonNull("finish_reason")) {
      finishReason = string(choice.path("finish_reason"));
    }
  }

  AgentMessage finish() {
    if (!done || role == null || finishReason == null || reasoningHighSurrogate != 0) {
      throw invalid();
    }
    var message = new LinkedHashMap<String, Object>();
    message.put("role", role);
    if (!content.isEmpty()) {
      message.put("content", content.toString());
    }
    if (!reasoning.isEmpty()) {
      message.put("reasoning_content", reasoning.toString());
    }
    if (!calls.isEmpty()) {
      var values = new ArrayList<Map<String, Object>>();
      for (var call : calls) {
        if (call.type == null) {
          throw invalid();
        }
        values.add(
            Map.of(
                "id",
                call.id.toString(),
                "type",
                call.type,
                "function",
                Map.of("name", call.name.toString(), "arguments", call.arguments.toString())));
      }
      message.put("tool_calls", values);
    }
    return AgentToolProtocol.assistantMessage(
        ModelHttpTransport.parseObject(
            ModelHttpTransport.encodeJson(
                Map.of(
                    "choices",
                    List.of(
                        Map.of("index", 0, "finish_reason", finishReason, "message", message))))));
  }

  private String reasoningDisplay(String part) {
    var display = new StringBuilder();
    for (int i = 0; i < part.length(); i++) {
      char c = part.charAt(i);
      if (reasoningHighSurrogate != 0) {
        if (!Character.isLowSurrogate(c)) {
          throw invalid();
        }
        display.append(reasoningHighSurrogate).append(c);
        reasoningHighSurrogate = 0;
      } else if (Character.isHighSurrogate(c)) {
        reasoningHighSurrogate = c;
      } else if (Character.isLowSurrogate(c)) {
        throw invalid();
      } else {
        display.append(c);
      }
    }
    return display.toString();
  }

  private static String string(JsonNode value) {
    if (!value.isString() || value.asString().indexOf(0) >= 0) {
      throw invalid();
    }
    return value.asString();
  }

  private static void append(StringBuilder target, String value, int limit) {
    if (value.length() > limit - target.length()) {
      throw invalid();
    }
    target.append(value);
  }

  private static TextModels.Failure invalid() {
    return new TextModels.Failure("model_invalid_response");
  }

  private static final class Call {
    final StringBuilder id = new StringBuilder();
    final StringBuilder name = new StringBuilder();
    final StringBuilder arguments = new StringBuilder();
    String type;
    AgentAnswerPreview preview;
  }
}
