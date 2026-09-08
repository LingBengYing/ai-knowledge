package com.evidence.rag.support;

import com.evidence.rag.client.vector.RetrievalProjection.Entry;
import com.evidence.rag.config.TextAdapterSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit HTTP protocol fixture, not a model-quality or real Milvus simulation. */
public final class AnswerProtocolServer implements AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern SCOPED_GENERATION =
      Pattern.compile(
          "\\(document_id == \"([A-Za-z0-9._:-]+)\" && revision_id == \"([A-Za-z0-9._:-]+)\"\\)");
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  public final List<Request> requests = new CopyOnWriteArrayList<>();

  public record Request(String path, JsonNode body) {}

  public AnswerProtocolServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::serve);
    server.start();
  }

  public URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public Map<String, Object> environment() {
    var values = new LinkedHashMap<String, Object>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      values.put("RAG_" + kind + "_BASE_URL", endpoint().toString());
      values.put("RAG_" + kind + "_MODEL", "fixture-answer-model");
      values.put("RAG_" + kind + "_API_KEY", "synthetic-answer-model-credential");
    }
    values.put("RAG_EMBEDDING_DIMENSIONS", "2");
    values.put("RAG_EMBEDDING_REVISION", "fixture-v1");
    values.put("RAG_MILVUS_ENDPOINT", endpoint().toString());
    values.put("RAG_MILVUS_TOKEN", "synthetic-answer-projection-credential");
    values.put("RAG_MILVUS_COLLECTION", "java_answer_http_fixture");
    values.put("RAG_WORKSPACE_ID", "org-main");
    values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    values.put("RAG_TEXT_DEADLINE_MS", "5000");
    return values;
  }

  public void install(List<Entry> published) {
    published.forEach(entry -> entries.put(entry.segmentId(), entry));
  }

  private void serve(HttpExchange exchange) throws IOException {
    try (exchange) {
      JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
      String path = exchange.getRequestURI().getPath();
      requests.add(new Request(path, body));
      Object response;
      if (path.equals("/embeddings")) {
        var vectors = new ArrayList<Object>();
        for (int index = 0; index < body.path("input").size(); index++) {
          vectors.add(Map.of("index", index, "embedding", List.of(1.0, 0.0)));
        }
        response = Map.of("data", vectors);
      } else if (path.equals("/rerank")) {
        var results = new ArrayList<Object>();
        for (int index = 0; index < body.path("documents").size(); index++) {
          results.add(Map.of("index", index, "relevance_score", 100.0 - index));
        }
        response = Map.of("results", results);
      } else if (path.equals("/chat/completions")) {
        var input = JSON.readTree(body.path("messages").get(1).path("content").asString());
        var quotes = new ArrayList<Object>();
        for (var evidence : input.path("evidence")) {
          quotes.add(
              Map.of(
                  "evidence_id",
                  evidence.path("evidence_id").asString(),
                  "quote",
                  evidence.path("text").asString()));
        }
        response =
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
                            JSON.writeValueAsString(
                                Map.of("refused", quotes.isEmpty(), "quotes", quotes))))));
      } else {
        Object data;
        if (path.endsWith("/collections/has")) {
          data = Map.of("has", true);
        } else if (path.endsWith("/collections/describe")) {
          data = description();
        } else if (path.endsWith("/indexes/describe")) {
          boolean dense = body.path("indexName").asString().equals("dense_index");
          data =
              List.of(
                  Map.of(
                      "indexName",
                      dense ? "dense_index" : "sparse_index",
                      "fieldName",
                      dense ? "dense" : "sparse",
                      "indexType",
                      dense ? "FLAT" : "SPARSE_INVERTED_INDEX",
                      "metricType",
                      dense ? "COSINE" : "BM25",
                      "indexState",
                      "Finished"));
        } else if (path.endsWith("/entities/search")) {
          data = search(body);
        } else {
          exchange.sendResponseHeaders(405, -1);
          return;
        }
        response = Map.of("code", 0, "data", data);
      }
      byte[] bytes = JSON.writeValueAsBytes(response);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private List<Map<String, Object>> search(JsonNode request) {
    String filter = request.path("filter").asString();
    if (!filter.startsWith("workspace_id == \"org-main\" && (")) {
      return List.of();
    }
    var scope = new LinkedHashMap<String, String>();
    var matcher = SCOPED_GENERATION.matcher(filter);
    while (matcher.find()) {
      scope.put(matcher.group(1), matcher.group(2));
    }
    return entries.values().stream()
        .filter(
            entry ->
                entry.workspaceId().equals("org-main")
                    && entry.revisionId().equals(scope.get(entry.documentId())))
        .sorted(java.util.Comparator.comparing(Entry::segmentId))
        .limit(request.path("limit").asInt())
        .map(
            entry ->
                Map.<String, Object>of(
                    "id",
                    entry.segmentId(),
                    "workspace_id",
                    entry.workspaceId(),
                    "document_id",
                    entry.documentId(),
                    "revision_id",
                    entry.revisionId(),
                    "distance",
                    0.9,
                    "text",
                    "UNTRUSTED projection body must never become an answer"))
        .toList();
  }

  private Map<String, Object> description() {
    var values = new LinkedHashMap<String, String>();
    environment().forEach((key, value) -> values.put(key, value.toString()));
    String embedding = TextAdapterSettings.load(values).projection().embeddingIdentity();
    List<Object> fields = new ArrayList<>();
    for (String name : List.of("id", "workspace_id", "document_id", "revision_id", "text")) {
      var params =
          new ArrayList<>(
              List.of(Map.of("key", "max_length", "value", name.equals("text") ? "16384" : "128")));
      if (name.equals("text")) {
        params.add(Map.of("key", "enable_analyzer", "value", "true"));
        params.add(Map.of("key", "analyzer_params", "value", "{\"type\":\"standard\"}"));
      }
      fields.add(
          Map.of(
              "name",
              name,
              "type",
              "VarChar",
              "primaryKey",
              name.equals("id"),
              "autoId",
              false,
              "nullable",
              false,
              "params",
              params));
    }
    fields.add(
        Map.of(
            "name",
            "dense",
            "type",
            "FloatVector",
            "primaryKey",
            false,
            "autoId",
            false,
            "nullable",
            false,
            "params",
            List.of(Map.of("key", "dim", "value", "2"))));
    fields.add(
        Map.of(
            "name",
            "sparse",
            "type",
            "SparseFloatVector",
            "primaryKey",
            false,
            "autoId",
            false,
            "nullable",
            false,
            "isFunctionOutput",
            true));
    return Map.of(
        "collectionName",
        "java_answer_http_fixture",
        "description",
        "evidence-rag-java-text-v1;workspace=org-main;embedding=" + embedding + ";dim=2",
        "autoId",
        false,
        "enableDynamicField",
        false,
        "consistencyLevel",
        "Strong",
        "fields",
        fields,
        "functions",
        List.of(
            Map.of(
                "name",
                "text_bm25",
                "type",
                1,
                "inputFieldNames",
                List.of("text"),
                "outputFieldNames",
                List.of("sparse"))));
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
