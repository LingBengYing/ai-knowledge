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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentProtocolClientTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

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
        String content =
            models.agentChat(
                List.of(
                    new AgentMessage("system", "Read original evidence"),
                    new AgentMessage("user", "整理资料")));
        assertEquals("Action: knowledge_search\nAction Input: {\"query\":\"灯塔\"}", content);
        assertEquals("/chat/completions", server.path.get());
        assertEquals("Bearer local-fixture-only", server.auth.get());
        assertEquals(2, server.request.get().path("messages").size());
        assertEquals("auto", server.request.get().path("tool_choice").asString());
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
  void nativeToolsCanonicalizeArgumentsAndKeepTextHistoryWithoutFakeToolMessages()
      throws Exception {
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
                new AgentMessage(
                    "assistant", "Action: knowledge_search\nAction Input: {\"query\":\"灯塔\"}"),
                new AgentMessage("user", "Observation: {\"sources\":[]}"));
        String content = models.agentChat(history);
        assertEquals(
            "Action: " + example.getKey() + "\nAction Input: " + example.getValue(), content);
        assertEquals(2, content.lines().count());
        assertEquals(3, server.request.get().path("messages").size());
        assertEquals("user", server.request.get().path("messages").get(2).path("role").asString());
        assertFalse(server.request.get().path("messages").get(1).has("tool_calls"));
      }
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

  @Test
  void acceptsIndexedCallsAndPreservesEveryReadonlyBatchCall() throws Exception {
    try (var server = new Server();
        var models = models(server)) {
      var first = indexedCall("call-1", 0, "knowledge_search", "{\"query\":\"需求\"}");
      var second = indexedCall("call-2", 1, "knowledge_search", "{\"query\":\"验收\"}");
      server.reply.set(callsReply(List.of(first)));
      assertEquals(
          "Action: knowledge_search\nAction Input: {\"query\":\"需求\"}",
          models.agentChat(List.of(new AgentMessage("user", "question"))));
      server.reply.set(callsReply(List.of(first, second)));
      String output = models.agentChat(List.of(new AgentMessage("user", "question")));
      assertEquals("Action: knowledge_batch", output.lines().findFirst().orElseThrow());
      var batch = JSON.readTree(output.substring(output.indexOf("Action Input: ") + 14));
      assertEquals(2, batch.path("calls").size());
      assertEquals("knowledge_search", batch.path("calls").get(0).path("name").asString());
      assertEquals("需求", batch.path("calls").get(0).path("arguments").path("query").asString());
      assertEquals("验收", batch.path("calls").get(1).path("arguments").path("query").asString());
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
        assertEquals(3, server.request.get().size());
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
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
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
