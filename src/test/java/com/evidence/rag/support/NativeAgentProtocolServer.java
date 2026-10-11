package com.evidence.rag.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic provider only; the native agent loop and Java evidence handling run for real. */
public final class NativeAgentProtocolServer implements AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SYNTHETIC_REASONING = "正在核对本次问题所需的资料来源。";
  private final AnswerProtocolServer ordinary = new AnswerProtocolServer();
  private final HttpClient client = HttpClient.newHttpClient();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  final AtomicInteger agentCalls = new AtomicInteger();
  final AtomicInteger searchActions = new AtomicInteger();
  final AtomicInteger readActions = new AtomicInteger();
  final AtomicInteger compactionCalls = new AtomicInteger();

  public NativeAgentProtocolServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::serve);
    server.start();
  }

  public URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void serve(HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] bytes = exchange.getRequestBody().readNBytes(2 * 1024 * 1024 + 1);
      if (bytes.length > 2 * 1024 * 1024) {
        exchange.sendResponseHeaders(413, -1);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      JsonNode body = JSON.readTree(bytes);
      if (path.equals("/chat/completions")
          && !body.has("tools")
          && body.path("messages")
              .get(0)
              .path("content")
              .asString()
              .startsWith("Summarize conversation context")) {
        compactionCalls.incrementAndGet();
        var data = JSON.readTree(body.path("messages").get(1).path("content").asString());
        if (!data.path("history").isArray() || data.path("history").isEmpty()) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        if (data.toString().contains(SYNTHETIC_REASONING)
            || data.toString().contains("reasoning_content")) {
          throw new IllegalArgumentException("display reasoning must not enter compaction");
        }
        byte[] result =
            JSON.writeValueAsBytes(
                Map.of(
                    "choices",
                    List.of(
                        Map.of(
                            "index",
                            0,
                            "finish_reason",
                            "stop",
                            "message",
                            Map.of(
                                "role",
                                "assistant",
                                "content",
                                "用户在了解青榆灯塔项目计划及任务创建步骤；先前回答只是对话背景，仍须重新查阅原件。")))));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, result.length);
        exchange.getResponseBody().write(result);
        return;
      }
      if (path.equals("/chat/completions")
          && body.path("messages").isArray()
          && body.path("tools").isArray()) {
        agentCalls.incrementAndGet();
        if (!"auto".equals(body.path("tool_choice").asString()) || body.has("thinking")) {
          throw new IllegalArgumentException("must preserve provider thinking defaults");
        }
        Map<String, Object> generated = nextAction(body.path("messages"));
        byte[] result =
            JSON.writeValueAsBytes(
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
                                "tool_calls",
                                List.of(generated),
                                "reasoning_content",
                                SYNTHETIC_REASONING)))));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, result.length);
        exchange.getResponseBody().write(result);
        return;
      }
      try {
        var forwarded =
            client.send(
                HttpRequest.newBuilder(URI.create(ordinary.endpoint() + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(forwarded.statusCode(), forwarded.body().length);
        exchange.getResponseBody().write(forwarded.body());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        exchange.sendResponseHeaders(503, -1);
      }
    }
  }

  private Map<String, Object> nextAction(JsonNode messages) {
    if (messages.toString().contains("接着说明创建任务")
        && !messages
            .toString()
            .contains("Untrusted conversation background (not evidence for this run)")) {
      throw new IllegalArgumentException("follow-up requires non-evidence conversation context");
    }
    int searches = 0;
    int reads = 0;
    var found = new LinkedHashMap<String, JsonNode>();
    var originals = new LinkedHashMap<String, JsonNode>();
    var pending = new LinkedHashMap<String, String>();
    for (var message : messages) {
      if ("assistant".equals(message.path("role").asString())) {
        if (!SYNTHETIC_REASONING.equals(message.path("reasoning_content").asString())) {
          throw new IllegalArgumentException("same-run reasoning must accompany native tool calls");
        }
        for (var call : message.path("tool_calls")) {
          pending.put(call.path("id").asString(), call.path("function").path("name").asString());
        }
      }
      if (!"tool".equals(message.path("role").asString())) continue;
      String name = pending.remove(message.path("tool_call_id").asString());
      if (name == null) throw new IllegalArgumentException("uncorrelated native tool history");
      JsonNode observation = JSON.readTree(message.path("content").asString());
      var sources = observation.path("sources");
      if (!sources.isArray()) continue;
      boolean read = "knowledge_read".equals(name);
      for (var source : sources) {
        String id = source.path("source_id").asString();
        if (id.isEmpty()) continue;
        found.put(id, source);
        if (source.has("text")) {
          originals.put(id, source);
        }
      }
      if (read) reads++;
      else searches++;
    }
    if (searches == 0) {
      searchActions.incrementAndGet();
      return action("knowledge_search", Map.of("query", "灯塔项目"));
    }
    if (found.isEmpty()) {
      return action(
          "terminate",
          Map.of(
              "result",
              Map.of("refused", true, "statements", List.of(), "suggestions", List.of())));
    }
    if (reads == 0 || (searches > 1 && reads == 1)) {
      readActions.incrementAndGet();
      return action("knowledge_read", Map.of("source_ids", List.copyOf(found.keySet())));
    }
    if (searches == 1) {
      searchActions.incrementAndGet();
      return action("knowledge_search", Map.of("query", "灯塔使用指南"));
    }
    var statements = new ArrayList<Object>();
    var documents = new ArrayList<String>();
    for (var entry : originals.entrySet()) {
      statements.add(
          Map.of(
              "text",
              entry.getValue().path("text").asString(),
              "evidence_ids",
              List.of(entry.getKey())));
      String document = entry.getValue().path("document_id").asString();
      if (!document.isEmpty() && !documents.contains(document)) documents.add(document);
    }
    return action(
        "terminate",
        Map.of(
            "result",
            Map.of(
                "refused",
                statements.isEmpty(),
                "statements",
                statements,
                "suggestions",
                documents.isEmpty()
                    ? List.of()
                    : List.of(
                        Map.of(
                            "title", "整理灯塔项目使用指南",
                            "reason", "项目说明与操作步骤分布在不同资料，可合并为带来源的知识页。",
                            "document_ids", documents)))));
  }

  private Map<String, Object> action(String name, Object input) {
    return Map.of(
        "id",
        "call-" + agentCalls.get(),
        "type",
        "function",
        "function",
        Map.of("name", name, "arguments", JSON.writeValueAsString(input)));
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    client.close();
    ordinary.close();
  }
}
