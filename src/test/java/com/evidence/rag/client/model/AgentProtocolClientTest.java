package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentProtocol;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentProtocolClientTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void automaticNativeToolChoiceCompletesWithoutForcingProviderThinkingMode() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      server.dynamicReply.set(
          request ->
              "auto".equals(request.path("tool_choice").asString())
                  ? toolReply("knowledge_search", "{\"query\":\"synthetic topic\"}")
                  : "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Synthetic ordinary reply.\"}}]}");
      assertEquals(
          assistant("call-fixture", "knowledge_search", "{\"query\":\"synthetic topic\"}"),
          models.agentChat(List.of(new AgentMessage("user", "synthetic question"))));
      assertEquals(1, server.calls.get(), "One request; no retry to repair ordinary text");
    }
  }

  @Test
  void rejectedProviderRequestDoesNotRetryOrSwitchToolMode() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      server.status.set(400);
      server.reply.set("{\"error\":\"private provider response\"}");
      var failure =
          assertThrows(
              TextModels.Failure.class,
              () -> models.agentChat(List.of(new AgentMessage("user", "synthetic question"))));
      assertEquals("model_http_failed", failure.code());
      assertEquals(1, server.calls.get());
      assertEquals("auto", server.request.get().path("tool_choice").asString());
      assertFalse(server.request.get().has("thinking"));
      assertFalse(failure.toString().contains("private provider response"));
    }
  }

  @Test
  void currentGenerationProtocolUsesNativeToolsWithoutJsonModeTokenLimitOrRevisionChange()
      throws Exception {
    try (var server = new Server()) {
      server.reply.set(toolReply("knowledge_search", "{\"query\":\"灯塔\"}"));
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(server.uri(), "synthetic", "local-fixture-only");
      try (var models =
          new OpenAiCompatibleModels(
              new OpenAiCompatibleModels.Configuration(
                  endpoint, endpoint, endpoint, 2, Duration.ofSeconds(2), 1024 * 1024, true))) {
        String revision = models.revision();
        AgentMessage message =
            models.agentChat(
                List.of(
                    new AgentMessage("system", "Read original evidence"),
                    new AgentMessage("user", "整理资料")));
        assertEquals(assistant("call-fixture", "knowledge_search", "{\"query\":\"灯塔\"}"), message);
        assertEquals("/chat/completions", server.path.get());
        assertEquals("Bearer local-fixture-only", server.auth.get());
        assertEquals(2, server.request.get().path("messages").size());
        assertEquals("auto", server.request.get().path("tool_choice").asString());
        assertFalse(server.request.get().has("thinking"));
        assertFalse(server.request.get().path("parallel_tool_calls").asBoolean());
        var names = new ArrayList<String>();
        for (var tool : server.request.get().path("tools")) {
          assertEquals("function", tool.path("type").asString());
          assertFalse(tool.path("function").has("strict"));
          names.add(tool.path("function").path("name").asString());
          assertEquals("object", tool.path("function").path("parameters").path("type").asString());
          assertFalse(
              tool.path("function").path("parameters").path("additionalProperties").asBoolean());
        }
        assertEquals(List.of("knowledge_search", "knowledge_read", "terminate"), names);
        assertFalse(server.request.get().has("max_tokens"));
        assertFalse(server.request.get().has("max_completion_tokens"));
        assertFalse(server.request.get().has("response_format"));
        assertEquals(revision, models.revision());
        for (String reason : List.of("length", "stop", "content_filter")) {
          server.reply.set(
              "{\"choices\":[{\"index\":0,\"finish_reason\":\""
                  + reason
                  + "\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}");
          int before = server.calls.get();
          assertThrows(
              TextModels.Failure.class,
              () -> models.agentChat(List.of(new AgentMessage("user", "question"))));
          assertEquals(before + 1, server.calls.get(), "No automatic retries");
        }
      }
    }
  }

  @Test
  void automaticToolChoiceDoesNotOverrideAnyProvidersThinkingMode() {
    var messages = List.of(new AgentMessage("user", "synthetic question"));
    for (String url :
        List.of(
            "https://api.deepseek.com",
            "https://api.deepseek.com/",
            "https://api.deepseek.com/v1",
            "https://api.deepseek.com/v1/",
            "https://API.DEEPSEEK.COM/v1")) {
      var request =
          AgentToolProtocol.request(
              new OpenAiCompatibleModels.Endpoint(URI.create(url), "any-model", "private-key"),
              messages);
      assertEquals("auto", request.get("tool_choice"));
      assertFalse(request.containsKey("thinking"));
      assertEquals(messages, request.get("messages"));
      assertFalse(request.toString().contains("private-key"));
      assertFalse(request.containsKey("max_tokens"));
      assertFalse(request.containsKey("max_completion_tokens"));
      assertFalse(request.containsKey("reasoning_effort"));
    }
    for (String url :
        List.of(
            "https://api.siliconflow.cn/v1",
            "https://api.deepseek.com.example.com/v1",
            "https://deepseek.com/v1",
            "https://proxy.example.com/deepseek",
            "http://127.0.0.1:18080/v1")) {
      var request =
          AgentToolProtocol.request(
              new OpenAiCompatibleModels.Endpoint(URI.create(url), "deepseek-flash", "private-key"),
              messages);
      assertEquals("auto", request.get("tool_choice"));
      assertFalse(request.containsKey("thinking"), "No guessed vendor options: " + url);
      assertFalse(request.containsKey("reasoning_effort"));
      assertEquals("deepseek-flash", request.get("model"));
    }
  }

  @Test
  void ordinaryAssistantTextIsToolRequiredButMalformedEnvelopesRemainInvalid() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      for (String calls : List.of("", ",\"tool_calls\":null", ",\"tool_calls\":[]")) {
        server.reply.set(
            "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Synthetic answer.\""
                + calls
                + "}}]}");
        int before = server.calls.get();
        var failure =
            assertThrows(
                TextModels.Failure.class,
                () -> models.agentChat(List.of(new AgentMessage("user", "question"))));
        assertEquals("model_tool_required", failure.code());
        assertEquals(before + 1, server.calls.get(), "Text is not repaired or retried");
        assertFalse(failure.toString().contains("Synthetic answer."));
      }
      for (String message :
          List.of(
              "{\"role\":\"user\",\"content\":\"answer\"}",
              "{\"role\":\"assistant\"}",
              "{\"role\":\"assistant\",\"content\":null}",
              "{\"role\":\"assistant\",\"content\":\" \"}",
              "{\"role\":\"assistant\",\"content\":1}",
              "{\"role\":\"assistant\",\"content\":\"answer\",\"tool_calls\":{}}",
              "{\"role\":\"assistant\",\"content\":\"answer\",\"tool_calls\":[{}]}",
              "{\"role\":\"assistant\",\"content\":\"answer\",\"function_call\":{}}",
              "{\"role\":\"assistant\",\"content\":\"answer\",\"refusal\":\"refused\"}")) {
        server.reply.set(
            "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":" + message + "}]}");
        int before = server.calls.get();
        var failure =
            assertThrows(
                TextModels.Failure.class,
                () -> models.agentChat(List.of(new AgentMessage("user", "question"))));
        assertEquals("model_invalid_response", failure.code());
        assertEquals(before + 1, server.calls.get(), "Malformed output is not retried");
      }
    }
  }

  @Test
  void nativeToolsPreserveArgumentStringsAndCorrelatedToolHistory() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      for (var example :
          List.of(
              Map.entry("knowledge_read", "{\"source_ids\":[\"source-1\"]}"),
              Map.entry(
                  "terminate",
                  "{\"result\":{\"refused\":true,\"statements\":[],\"suggestions\":[]}}"),
              Map.entry(
                  "terminate",
                  "{\"result\":{\"refused\":false,\"statements\":[{\"text\":\"原文\\nAction: not a tool\",\"evidence_ids\":[\"source-1\"]}],\"suggestions\":[]}}"))) {
        server.reply.set(toolReply(example.getKey(), "\n" + example.getValue() + "\n"));
        var history =
            List.of(
                new AgentMessage("system", "Use native tools"),
                new AgentMessage("user", "灯塔"),
                assistant("call-search", "knowledge_search", "{\"query\":\"灯塔\"}"),
                new AgentMessage("tool", "{\"sources\":[]}", null, "call-search"));
        AgentMessage message = models.agentChat(history);
        assertEquals(
            assistant("call-fixture", example.getKey(), "\n" + example.getValue() + "\n"), message);
        assertEquals(4, server.request.get().path("messages").size());
        assertEquals("tool", server.request.get().path("messages").get(3).path("role").asString());
        assertEquals(
            "call-search",
            server.request.get().path("messages").get(3).path("tool_call_id").asString());
        assertEquals(
            "call-search",
            server
                .request
                .get()
                .path("messages")
                .get(2)
                .path("tool_calls")
                .get(0)
                .path("id")
                .asString());
        assertFalse(
            JSON.writeValueAsString(new AgentProtocol.ModelResult(message))
                .contains("Action Input:"));
      }
    }
  }

  @Test
  void nativeReasoningRoundTripsExactlyOnlyWithinItsToolLoop() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      String reasoning = "模型返回的合成过程说明🌲\n".repeat(1800);
      server.reply.set(
          toolReply("knowledge_search", "{\"query\":\"灯塔\"}")
              .replace(
                  "\"content\":null",
                  "\"content\":null,\"reasoning_content\":" + JSON.writeValueAsString(reasoning)));
      var first = models.agentChat(List.of(new AgentMessage("user", "question")));
      assertEquals(reasoning, first.reasoningContent());
      server.reply.set(toolReply("knowledge_read", "{\"source_ids\":[\"source-1\"]}"));
      var next =
          models.agentChat(
              List.of(
                  new AgentMessage("user", "question"),
                  first,
                  new AgentMessage("tool", "{\"sources\":[]}", null, "call-fixture")));
      assertEquals(
          reasoning,
          server.request.get().path("messages").get(1).path("reasoning_content").asString());
      assertEquals(2, server.calls.get());
      assertEquals(null, next.reasoningContent());
      assertFalse(first.toString().contains("合成过程"));
      assertThrows(
          RuntimeException.class, () -> new AgentMessage("user", "x", null, null, reasoning));
      assertThrows(
          RuntimeException.class,
          () -> new AgentMessage("assistant", "old answer", null, null, reasoning));
      assertThrows(
          RuntimeException.class,
          () -> new AgentMessage("tool", "{}", null, "call-fixture", reasoning));
      assertThrows(
          RuntimeException.class,
          () -> new AgentProtocol.RunRequest("id", "q", "token", List.of(first), ""));
      assertThrows(RuntimeException.class, () -> models.compactConversation("", List.of(first)));
      assertEquals(
          2, server.calls.get(), "Conversation compaction never forwards native reasoning history");
    }
  }

  @Test
  void malformedNativeReasoningFailsWithoutRetryAndCountsTowardContextBoundary() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      for (Object reasoning : List.of(3, List.of("x"), "x".repeat(262145), "invalid\u0000field")) {
        server.reply.set(
            toolReply("knowledge_search", "{\"query\":\"x\"}")
                .replace(
                    "\"content\":null",
                    "\"content\":null,\"reasoning_content\":"
                        + JSON.writeValueAsString(reasoning)));
        int before = server.calls.get();
        assertEquals(
            "model_invalid_response",
            assertThrows(
                    TextModels.Failure.class,
                    () -> models.agentChat(List.of(new AgentMessage("user", "q"))))
                .code());
        assertEquals(before + 1, server.calls.get());
      }
      var nativeCall = assistant("call", "knowledge_search", "{\"query\":\"x\"}");
      var large =
          new AgentMessage("assistant", null, nativeCall.toolCalls(), null, "x".repeat(262140));
      int before = server.calls.get();
      assertThrows(
          RuntimeException.class,
          () -> models.agentChat(List.of(large, new AgentMessage("tool", "{}", null, "call"))));
      assertEquals(before, server.calls.get(), "Full native history is bounded, never truncated");
    }
  }

  @Test
  void invalidNativeToolsNeverFallBackToTextOrRetry() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      var invalid = new ArrayList<String>();
      for (String arguments :
          List.of(
              "[]",
              "{}",
              "{\"query\":1}",
              "{\"query\":\"\"}",
              "{\"query\":\"x\",\"url\":\"https://example.com\"}",
              "{\"query\":\"first\",\"query\":\"second\"}",
              "{\"query\":\"x\"} {}")) {
        invalid.add(toolReply("knowledge_search", arguments));
      }
      invalid.add(toolReply("run_sql", "{\"query\":\"x\"}"));
      invalid.add(toolReply("knowledge_read", "{\"source_ids\":[]}"));
      invalid.add(toolReply("knowledge_read", "{\"source_ids\":[\"source-1\",\"source-1\"]}"));
      invalid.add(toolReply("terminate", "{\"result\":\"free answer\"}"));
      invalid.add(
          toolReply(
              "terminate",
              "{\"result\":{\"refused\":\"false\",\"statements\":[],\"suggestions\":[]}}"));
      invalid.add(
          toolReply(
              "terminate",
              "{\"result\":{\"refused\":false,\"statements\":[{\"text\":\"answer\",\"evidence_ids\":[]}],\"suggestions\":[]}}"));
      var valid = JSON.readTree(toolReply("knowledge_search", "{\"query\":\"x\"}"));
      var call = valid.path("choices").get(0).path("message").path("tool_calls").get(0);
      for (Object calls : List.of(List.of(), List.of(call, call), "invalid")) {
        invalid.add(
            JSON.writeValueAsString(
                Map.of(
                    "choices",
                    List.of(
                        Map.of(
                            "index",
                            0,
                            "finish_reason",
                            "tool_calls",
                            "message",
                            Map.of(
                                "role",
                                "assistant",
                                "content",
                                "Action: knowledge_search\nAction Input: {\"query\":\"x\"}",
                                "tool_calls",
                                calls))))));
      }
      invalid.add(
          toolReply("knowledge_search", "{\"query\":\"x\"}")
              .replace("\"type\":\"function\"", "\"type\":\"shell\""));
      invalid.add(
          toolReply("knowledge_search", "{\"query\":\"x\"}")
              .replace("\"role\":\"assistant\"", "\"role\":\"user\""));
      invalid.add(
          toolReply("knowledge_search", "{\"query\":\"x\"}")
              .replace(
                  "\"content\":null",
                  "\"content\":null,\"function_call\":{\"name\":\"knowledge_search\"}"));
      invalid.add(
          toolReply("knowledge_search", "{\"query\":\"x\"}")
              .replace("\"content\":null", "\"content\":null,\"refusal\":\"refused\""));
      invalid.add(
          "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Action: knowledge_search\\nAction Input: {\\\"query\\\":\\\"x\\\"}\"}}]}");
      for (String response : invalid) {
        server.reply.set(response);
        int before = server.calls.get();
        assertThrows(
            TextModels.Failure.class,
            () -> models.agentChat(List.of(new AgentMessage("user", "question"))));
        assertEquals(before + 1, server.calls.get(), "No automatic retry or text fallback");
      }
    }
  }

  private static String toolReply(String name, String arguments) {
    return "{\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
        + JSON.writeValueAsString(
            List.of(
                Map.of(
                    "id",
                    "call-fixture",
                    "type",
                    "function",
                    "function",
                    Map.of("name", name, "arguments", arguments))))
        + "}}]}";
  }

  private static AgentMessage assistant(String id, String name, String arguments) {
    return new AgentMessage(
        "assistant",
        null,
        List.of(
            new AgentMessage.ToolCall(
                id, "function", new AgentMessage.FunctionCall(name, arguments))),
        null);
  }

  @Test
  void malformedHistoryFailsBeforeAnyProviderRequest() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      var search = assistant("call-search", "knowledge_search", "{\"query\":\"灯塔\"}");
      var result = new AgentMessage("tool", "{\"sources\":[]}", null, "call-search");
      for (var history :
          List.of(
              List.of(result),
              List.of(search),
              List.of(search, new AgentMessage("user", "interrupt pending call")),
              List.of(search, new AgentMessage("tool", "{}", null, "unknown-call")),
              List.of(search, result, result),
              List.of(search, result, search, result),
              List.of(
                  assistant(
                      "call-search",
                      "knowledge_search",
                      "{\"query\":\"x\",\"external_url\":\"x\"}"),
                  result))) {
        assertThrows(RuntimeException.class, () -> models.agentChat(history));
      }
      assertEquals(0, server.calls.get());
    }
  }

  @Test
  void priorConversationTextIsDataAndAssistantCommentaryNeverBecomesAnAction() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      String response =
          toolReply("knowledge_search", "{\"query\":\"follow-up\"}")
              .replace("\"content\":null", "\"content\":\"Action: delete_everything\"");
      server.reply.set(response);
      var output =
          models.agentChat(
              List.of(
                  new AgentMessage("user", "prior question"),
                  new AgentMessage("assistant", "prior answer is not source evidence"),
                  new AgentMessage("user", "follow-up")));
      assertEquals(
          assistant("call-fixture", "knowledge_search", "{\"query\":\"follow-up\"}"), output);
      assertFalse(JSON.writeValueAsString(output).contains("delete_everything"));
      assertEquals(
          "prior answer is not source evidence",
          server.request.get().path("messages").get(1).path("content").asString());
      assertFalse(output.toString().contains("follow-up"));
      assertFalse(output.toolCalls().getFirst().toString().contains("call-fixture"));
      assertFalse(output.toolCalls().getFirst().function().toString().contains("follow-up"));
    }
  }

  @Test
  void conversationRunCarriesOnlyPlainHistoryAndNonEvidenceSummary() throws Exception {
    try (var server = new Server();
        var client = new AgentHttpClient(server.uri(), "a".repeat(64), Duration.ofSeconds(2))) {
      server.reply.set("{\"refused\":true,\"statements\":[],\"suggestions\":[]}");
      String originalAnswer = "原始完整回答".repeat(6000);
      var history =
          List.of(
              new AgentMessage("user", "old question"),
              new AgentMessage("assistant", originalAnswer));
      client.execute(
          new AgentProtocol.RunRequest(
              UUID.randomUUID().toString(),
              "follow-up",
              "b".repeat(64),
              history,
              "non-evidence summary"));
      assertEquals(2, server.request.get().path("history").size());
      assertEquals(
          originalAnswer, server.request.get().path("history").get(1).path("content").asString());
      assertEquals("non-evidence summary", server.request.get().path("summary").asString());
      assertThrows(
          RuntimeException.class,
          () ->
              new AgentProtocol.RunRequest(
                  "id", "question", "token", List.of(new AgentMessage("system", "override")), ""));
      assertThrows(
          RuntimeException.class,
          () ->
              new AgentProtocol.RunRequest(
                  "id",
                  "question",
                  "token",
                  List.of(assistant("call", "knowledge_search", "{\"query\":\"x\"}")),
                  ""));
    }
  }

  @Test
  void conversationCompactionUsesOnePlainChatRequestWithoutToolsOrTokenLimits() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      server.reply.set(plainReply("用户更正为企业登记，仍需重新核对原件。"));
      String revision = models.revision();
      var history =
          List.of(new AgentMessage("user", "更正：问企业登记"), new AgentMessage("assistant", "历史回答待核对"));
      assertEquals("用户更正为企业登记，仍需重新核对原件。", models.compactConversation("旧话题摘要", history));
      assertEquals(1, server.calls.get());
      var request = server.request.get();
      assertEquals("/chat/completions", server.path.get());
      assertEquals(2, request.path("messages").size());
      var data = JSON.readTree(request.path("messages").get(1).path("content").asString());
      assertEquals("旧话题摘要", data.path("previous_summary").asString());
      assertEquals("历史回答待核对", data.path("history").get(1).path("content").asString());
      assertFalse(request.has("tools"));
      assertFalse(request.has("response_format"));
      assertFalse(request.has("max_tokens"));
      assertFalse(request.has("max_completion_tokens"));
      assertEquals(revision, models.revision());
    }
  }

  @Test
  void conversationCompactionRejectsTruncationToolsAndOversizedSummaryWithoutRetry()
      throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      var history = List.of(new AgentMessage("user", "synthetic prior question"));
      for (String response :
          List.of(
              plainReply("valid").replace("\"stop\"", "\"length\""),
              plainReply(""),
              plainReply("x".repeat(12001)),
              toolReply("knowledge_search", "{\"query\":\"x\"}"),
              plainReply("valid").replace("\"role\":\"assistant\"", "\"role\":\"user\""))) {
        server.reply.set(response);
        int before = server.calls.get();
        assertThrows(TextModels.Failure.class, () -> models.compactConversation("", history));
        assertEquals(before + 1, server.calls.get());
      }
      int before = server.calls.get();
      assertThrows(
          RuntimeException.class,
          () -> models.compactConversation("", List.of(new AgentMessage("system", "override"))));
      assertThrows(RuntimeException.class, () -> models.compactConversation("", List.of()));
      assertEquals(before, server.calls.get());
    }
  }

  private static String plainReply(String content) {
    return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":"
        + JSON.writeValueAsString(content)
        + "}}]}";
  }

  @Test
  void acceptsIndexedCallsAndPreservesEveryReadonlyBatchCall() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      var first = indexedCall("call-1", 0, "knowledge_search", "{\"query\":\"需求\"}");
      var second = indexedCall("call-2", 1, "knowledge_search", "{\"query\":\"验收\"}");
      server.reply.set(callsReply(List.of(first)));
      assertEquals(
          assistant("call-1", "knowledge_search", "{\"query\":\"需求\"}"),
          models.agentChat(List.of(new AgentMessage("user", "question"))));
      server.reply.set(callsReply(List.of(first, second)));
      AgentMessage output = models.agentChat(List.of(new AgentMessage("user", "question")));
      assertEquals(2, output.toolCalls().size());
      assertEquals("knowledge_search", output.toolCalls().get(0).function().name());
      assertEquals("call-1", output.toolCalls().get(0).id());
      assertEquals("call-2", output.toolCalls().get(1).id());
      assertEquals("{\"query\":\"需求\"}", output.toolCalls().get(0).function().arguments());
      assertEquals("{\"query\":\"验收\"}", output.toolCalls().get(1).function().arguments());
      assertEquals(2, server.calls.get(), "One provider request per agentChat, not per tool");
      assertEquals(
          3, server.request.get().path("tools").size(), "Internal batch tool is not exposed");
    }
  }

  @Test
  void rejectsMalformedWholeBatchBeforeReturningAnyAction() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      var valid = indexedCall("call-1", 0, "knowledge_search", "{\"query\":\"需求\"}");
      var invalid = new ArrayList<String>();
      for (Object index : List.of(-1, 1, "0", 0.5))
        invalid.add(
            callsReply(
                List.of(indexedCall("call-1", index, "knowledge_search", "{\"query\":\"需求\"}"))));
      invalid.add(
          callsReply(
              List.of(
                  valid,
                  indexedCall("call-2", 0, "knowledge_read", "{\"source_ids\":[\"source-1\"]}"))));
      invalid.add(
          callsReply(
              List.of(
                  valid,
                  indexedCall(
                      "call-2",
                      1,
                      "terminate",
                      "{\"result\":{\"refused\":true,\"statements\":[],\"suggestions\":[]}}"))));
      invalid.add(callsReply(List.of(valid, indexedCall("call-2", 1, "run_sql", "{}"))));
      invalid.add(
          callsReply(
              List.of(valid, indexedCall("call-2", 1, "knowledge_read", "{\"source_ids\":[]}"))));
      invalid.add(
          callsReply(
              List.of(
                  valid, indexedCall("call-1", 1, "knowledge_search", "{\"query\":\"重复ID\"}"))));
      var oversized = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < 17; i++)
        oversized.add(indexedCall("call-" + i, i, "knowledge_search", "{\"query\":\"需求\"}"));
      invalid.add(callsReply(oversized));
      for (String response : invalid) {
        server.reply.set(response);
        int before = server.calls.get();
        assertThrows(
            TextModels.Failure.class,
            () -> models.agentChat(List.of(new AgentMessage("user", "question"))));
        assertEquals(before + 1, server.calls.get());
      }
    }
  }

  private static Map<String, Object> indexedCall(
      String id, Object index, String name, String arguments) {
    return Map.of(
        "id",
        id,
        "index",
        index,
        "type",
        "function",
        "function",
        Map.of("name", name, "arguments", arguments));
  }

  private static String callsReply(Object calls) {
    return JSON.writeValueAsString(
        Map.of(
            "choices",
            List.of(
                Map.of(
                    "index",
                    0,
                    "finish_reason",
                    "tool_calls",
                    "message",
                    Map.of("role", "assistant", "tool_calls", calls)))));
  }

  private static OpenAiCompatibleModels models(Server server) {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(server.uri(), "synthetic", "local-fixture-only");
    return new OpenAiCompatibleModels(
        new OpenAiCompatibleModels.Configuration(
            endpoint, endpoint, endpoint, 2, Duration.ofSeconds(2), 1024 * 1024, true));
  }

  @Test
  void sidecarHasOneFixedLoopbackDestinationAndStrictStructuredProposal() throws Exception {
    try (var server = new Server()) {
      String token = UUID.randomUUID().toString();
      try (var client = new AgentHttpClient(server.uri(), token, Duration.ofSeconds(2))) {
        server.reply.set(
            "{\"refused\":false,\"statements\":[{\"text\":\"结果\",\"evidence_ids\":[\"source-1\"]}],\"suggestions\":[]}");
        var result =
            client.execute(
                new AgentProtocol.RunRequest(UUID.randomUUID().toString(), "问题", "b".repeat(64)));
        assertEquals("/v1/runs", server.path.get());
        assertEquals("Bearer " + token, server.auth.get());
        assertEquals(5, server.request.get().size());
        assertEquals(0, server.request.get().path("history").size());
        assertEquals("", server.request.get().path("summary").asString());
        assertEquals(List.of("source-1"), result.statements().getFirst().evidenceIds());
        for (String malformed :
            List.of(
                "{}",
                "{\"refused\":true,\"statements\":[],\"suggestions\":[],\"url\":\"external\"}",
                "{\"refused\":false,\"statements\":[{\"text\":\"bad\",\"evidence_ids\":[]}],\"suggestions\":[]}")) {
          server.reply.set(malformed);
          int before = server.calls.get();
          assertThrows(
              TextModels.Failure.class,
              () ->
                  client.execute(
                      new AgentProtocol.RunRequest(
                          UUID.randomUUID().toString(), "question", "b".repeat(64))));
          assertEquals(before + 1, server.calls.get());
        }
      }
      for (String uri :
          List.of(
              "http://example.com",
              "https://127.0.0.1",
              "http://127.0.0.1/other",
              "http://127.0.0.1?url=external")) {
        assertThrows(
            TextModels.Failure.class,
            () -> new AgentHttpClient(URI.create(uri), token, Duration.ofSeconds(2)));
      }
    }
  }

  @Test
  void sidecar502PreservesOnlyEnumeratedFailureCodesWithoutRetryOrRawContent() throws Exception {
    try (var server = new Server();
        var client =
            new AgentHttpClient(
                server.uri(), UUID.randomUUID().toString(), Duration.ofSeconds(2))) {
      server.status.set(502);
      for (String code :
          List.of(
              "agent_callback_failed",
              "agent_callback_invalid",
              "agent_model_invalid",
              "agent_model_tool_required",
              "agent_model_unavailable",
              "agent_model_timeout",
              "agent_invalid_action",
              "agent_invalid_tool_input",
              "agent_tool_failed",
              "agent_invalid_result",
              "agent_step_limit",
              "agent_timeout",
              "agent_execution_failed")) {
        server.reply.set("{\"error\":\"" + code + "\"}");
        int before = server.calls.get();
        var failure =
            assertThrows(
                TextModels.Failure.class,
                () ->
                    client.execute(
                        new AgentProtocol.RunRequest(
                            UUID.randomUUID().toString(), "synthetic question", "b".repeat(64))));
        assertEquals(code, failure.code());
        assertEquals(before + 1, server.calls.get());
      }
      for (String body :
          List.of(
              "{\"error\":\"private-provider-secret\"}",
              "{\"error\":\"agent_invalid_action\",\"details\":\"private-provider-secret\"}",
              "{\"error\":{\"code\":\"agent_invalid_action\"}}")) {
        server.reply.set(body);
        var failure =
            assertThrows(
                TextModels.Failure.class,
                () ->
                    client.execute(
                        new AgentProtocol.RunRequest(
                            UUID.randomUUID().toString(), "synthetic question", "b".repeat(64))));
        assertEquals("agent_unavailable", failure.code());
        assertFalse(failure.toString().contains("private-provider-secret"));
      }
    }
  }

  @Test
  void sidecarTimeoutUnreachableAndInvalidBodyRemainDistinct() throws Exception {
    String token = UUID.randomUUID().toString();
    var request =
        new AgentProtocol.RunRequest(
            UUID.randomUUID().toString(), "synthetic question", "b".repeat(64));
    URI stopped;
    try (var server = new Server()) {
      stopped = server.uri();
      try (var client = new AgentHttpClient(server.uri(), token, Duration.ofSeconds(1))) {
        server.status.set(502);
        for (String malformed :
            List.of(
                "private-provider-secret",
                "[]",
                "{\"error\":\"agent_timeout\"} {}",
                "{\"error\":\"agent_timeout\",\"error\":\"agent_step_limit\"}")) {
          server.reply.set(malformed);
          var failure = assertThrows(TextModels.Failure.class, () -> client.execute(request));
          assertEquals("agent_invalid_response", failure.code());
          assertFalse(failure.toString().contains("private-provider-secret"));
        }
      }
      server.delayMillis.set(250);
      try (var client = new AgentHttpClient(server.uri(), token, Duration.ofMillis(50))) {
        assertEquals(
            "agent_timeout",
            assertThrows(TextModels.Failure.class, () -> client.execute(request)).code());
      }
    }
    try (var client = new AgentHttpClient(stopped, token, Duration.ofSeconds(1))) {
      assertEquals(
          "agent_unavailable",
          assertThrows(TextModels.Failure.class, () -> client.execute(request)).code());
    }
  }

  private static final class Server implements AutoCloseable {
    final HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>("{}");
    final AtomicReference<Function<JsonNode, String>> dynamicReply = new AtomicReference<>();
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger delayMillis = new AtomicInteger();

    Server() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            try {
              Thread.sleep(delayMillis.get());
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            var responder = dynamicReply.get();
            byte[] bytes =
                (responder == null ? reply.get() : responder.apply(request.get()))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
