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

/** Synthetic model protocol only; DB-GPT itself and Java evidence handling run for real. */
public final class DbGptProtocolServer implements AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final AnswerProtocolServer ordinary = new AnswerProtocolServer();
  private final HttpClient client = HttpClient.newHttpClient();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  final AtomicInteger agentCalls = new AtomicInteger();
  final AtomicInteger searchActions = new AtomicInteger();
  final AtomicInteger readActions = new AtomicInteger();

  public DbGptProtocolServer() throws IOException {
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
          && body.path("messages").isArray()
          && body.path("messages").toString().contains("knowledge_search")) {
        agentCalls.incrementAndGet();
        String generated = nextAction(body.path("messages"));
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
                            Map.of("role", "assistant", "content", generated)))));
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

  private String nextAction(JsonNode messages) {
    int searches = 0;
    int reads = 0;
    var found = new LinkedHashMap<String, JsonNode>();
    var originals = new LinkedHashMap<String, JsonNode>();
    for (var message : messages) {
      String content = message.path("content").asString();
      int marker = content.indexOf("Observation:");
      if (marker < 0) continue;
      int start = content.indexOf('{', marker);
      int end = content.lastIndexOf('}');
      if (start < 0 || end < start) continue;
      JsonNode observation;
      try {
        observation = JSON.readTree(content.substring(start, end + 1));
      } catch (RuntimeException malformed) {
        continue;
      }
      var sources = observation.path("sources");
      if (!sources.isArray()) continue;
      boolean read = false;
      for (var source : sources) {
        String id = source.path("source_id").asString();
        if (id.isEmpty()) continue;
        found.put(id, source);
        if (source.has("text")) {
          originals.put(id, source);
          read = true;
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

  private static String action(String name, Object input) {
    return "Thought: synthetic protocol fixture\nAction: "
        + name
        + "\nAction Input: "
        + JSON.writeValueAsString(input);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    client.close();
    ordinary.close();
  }
}
