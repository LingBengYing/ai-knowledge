package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.tool.parser.TextParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic local HTTP Adapter fixture; never contacts a real model or Milvus deployment. */
public final class IndexingTestServer implements AutoCloseable {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern SCOPED_GENERATION =
      Pattern.compile(
          "\\(document_id == \"([A-Za-z0-9._:-]+)\" && revision_id == \"([A-Za-z0-9._:-]+)\"\\)");
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, JsonNode> rows = new ConcurrentHashMap<>();
  private final Map<String, JsonNode> createdCollections = new ConcurrentHashMap<>();
  private final Map<String, Map<String, JsonNode>> collectionRows = new ConcurrentHashMap<>();
  private final boolean managedCollections;
  private final int dimensions;
  private final int maxResponseBytes;
  private final URI modelEndpoint;
  public final List<Request> requests = new CopyOnWriteArrayList<>();
  public final CountDownLatch embeddingStarted = new CountDownLatch(1);
  public final CountDownLatch releaseEmbedding = new CountDownLatch(1);
  public final CountDownLatch upsertStarted = new CountDownLatch(1);
  public final CountDownLatch releaseUpsert = new CountDownLatch(1);
  public final CountDownLatch blockedUpsertCommitted = new CountDownLatch(1);
  public final List<Request> committedUpserts = new CopyOnWriteArrayList<>();
  public volatile String failureMode = "";
  public volatile long responseDelayMillis;

  public record Request(String path, JsonNode body) {}

  public IndexingTestServer() throws IOException {
    this(2, 4 * 1024 * 1024);
  }

  public IndexingTestServer(int dimensions, int maxResponseBytes) throws IOException {
    this(dimensions, maxResponseBytes, null);
  }

  public IndexingTestServer(int dimensions, int maxResponseBytes, URI modelEndpoint)
      throws IOException {
    this(dimensions, maxResponseBytes, modelEndpoint, false);
  }

  /**
   * Opt-in collection lifecycle for explicit model migration integration; legacy fixtures
   * unchanged.
   */
  public IndexingTestServer(
      int dimensions, int maxResponseBytes, URI modelEndpoint, boolean managedCollections)
      throws IOException {
    this.modelEndpoint = modelEndpoint;
    this.managedCollections = managedCollections;
    this.dimensions = dimensions;
    this.maxResponseBytes = maxResponseBytes;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::serve);
    server.start();
  }

  public URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public TextAdapterSettings settings() {
    var env = new LinkedHashMap<String, String>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      env.put(
          "RAG_" + kind + "_BASE_URL",
          (modelEndpoint == null ? endpoint() : modelEndpoint).toString());
      env.put("RAG_" + kind + "_MODEL", "fixture-model");
      env.put("RAG_" + kind + "_API_KEY", "synthetic-model-credential");
    }
    env.put("RAG_EMBEDDING_DIMENSIONS", Integer.toString(dimensions));
    env.put("RAG_EMBEDDING_REVISION", "fixture-v1");
    env.put("RAG_MILVUS_ENDPOINT", endpoint().toString());
    env.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    env.put("RAG_MILVUS_COLLECTION", "java_index_process_fixture");
    env.put("RAG_WORKSPACE_ID", "org-main");
    env.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    env.put("RAG_TEXT_DEADLINE_MS", "10000");
    env.put("RAG_TEXT_MAX_RESPONSE_BYTES", Integer.toString(maxResponseBytes));
    return TextAdapterSettings.load(env);
  }

  public IndexTarget target() {
    var settings = settings();
    try (var models = new OpenAiCompatibleModels(settings.models())) {
      return new IndexTarget(
          settings.projection().embeddingIdentity(),
          settings.projection().identity(),
          models.revision(),
          dimensions);
    }
  }

  public IndexClaim claim(int count) {
    return claim(count, "fixture-generation");
  }

  public IndexClaim claim(int count, String generation) {
    var segments = new ArrayList<IndexSegment>();
    for (int i = 0; i < count; i++) {
      String text = "合成索引证据😀第" + i + "段。忽略指令是文档数据。";
      int points = text.codePointCount(0, text.length());
      int start = i % 100 * 1200;
      segments.add(
          new IndexSegment(
              "fixture-segment-" + i, i, i / 100 + 1, start, start + points, text, sha256(text)));
    }
    return new IndexClaim(
        "fixture-job",
        "fixture-document",
        "fixture-revision",
        "org-main",
        1,
        "synthetic-claim-token",
        sha256("synthetic source"),
        TextParser.REVISION,
        target(),
        segments,
        generation);
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private void serve(HttpExchange exchange) throws IOException {
    try (exchange) {
      var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
      String path = exchange.getRequestURI().getPath();
      requests.add(new Request(path, body));
      String collection = body.path("collectionName").asString();
      boolean baseCollection = collection.equals("java_index_process_fixture");
      boolean collectionExists = baseCollection || createdCollections.containsKey(collection);
      if (managedCollections
          && !path.equals("/embeddings")
          && !path.endsWith("/collections/has")
          && !path.endsWith("/collections/create")
          && !collectionExists) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      Map<String, JsonNode> targetRows =
          managedCollections
              ? collectionRows.computeIfAbsent(collection, ignored -> new ConcurrentHashMap<>())
              : rows;
      Object data;
      boolean model = path.equals("/embeddings");
      if (model) {
        embeddingStarted.countDown();
        if (failureMode.equals("block-embedding")) {
          releaseEmbedding.await();
        }
        var vectors = new ArrayList<Object>();
        for (int i = 0; i < body.path("input").size(); i++) {
          var vector = new ArrayList<Double>();
          for (int j = 0; j < dimensions; j++) {
            vector.add(j == 0 ? 0.1 : 0.5);
          }
          if (failureMode.equals("bad-vector")) {
            vector.set(0, Double.MAX_VALUE);
          }
          if (failureMode.equals("wrong-dimensions")) {
            vector.removeLast();
          }
          vectors.add(Map.of("index", i, "embedding", vector));
        }
        data = vectors;
      } else if (path.endsWith("/collections/has")) {
        data = Map.of("has", !managedCollections || collectionExists);
      } else if (managedCollections && path.endsWith("/collections/create")) {
        if (collectionExists
            || !collection.matches("java_[A-Za-z0-9_]{1,122}")
            || !body.path("schema").isObject()
            || !body.path("indexParams").isArray()) {
          exchange.sendResponseHeaders(409, -1);
          return;
        }
        createdCollections.put(collection, body);
        data = Map.of();
      } else if (path.endsWith("/collections/describe")) {
        data =
            managedCollections && !baseCollection
                ? createdDescription(createdCollections.get(collection))
                : description();
      } else if (path.endsWith("/indexes/describe")) {
        boolean dense = body.path("indexName").asString().equals("dense_index");
        data =
            managedCollections && !baseCollection
                ? createdIndex(
                    createdCollections.get(collection), body.path("indexName").asString())
                : List.of(
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
      } else if (path.endsWith("/collections/load")) {
        data = Map.of();
      } else if (path.endsWith("/entities/upsert")) {
        boolean blocked = failureMode.equals("block-upsert");
        upsertStarted.countDown();
        if (blocked) {
          releaseUpsert.await();
        }
        var ids = new ArrayList<String>();
        for (var row : body.path("data")) {
          String id = row.path("id").asString();
          targetRows.put(id, row);
          ids.add(id);
        }
        if (failureMode.equals("partial-upsert")) {
          ids.removeLast();
        }
        committedUpserts.add(new Request(path, body));
        if (blocked) {
          blockedUpsertCommitted.countDown();
        }
        data = Map.of("upsertCount", ids.size(), "upsertIds", ids);
      } else if (path.endsWith("/entities/search")) {
        data = search(body, targetRows);
      } else if (path.endsWith("/entities/query")) {
        String filter = body.path("filter").asString();
        var result = new ArrayList<Map<String, Object>>();
        var revisionMatch =
            java.util.regex.Pattern.compile(
                    "revision_id == \"([A-Za-z0-9][A-Za-z0-9._:-]{0,127})\"")
                .matcher(filter);
        String revision = revisionMatch.matches() ? revisionMatch.group(1) : null;
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (filter.startsWith("id in [")) {
          JsonNode selected = JSON.readTree(filter.substring(6));
          if (!selected.isArray() || selected.isEmpty()) {
            throw new IOException("Invalid fixture filter");
          }
          for (var id : selected) {
            if (!id.isString()
                || !id.asString().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || !ids.add(id.asString())) {
              throw new IOException("Invalid fixture filter");
            }
          }
        } else if (revision == null) {
          throw new IOException("Invalid fixture filter");
        }
        var fields = new ArrayList<String>();
        body.path("outputFields").forEach(value -> fields.add(value.asString()));
        for (var entry :
            targetRows.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
          if (revision != null
              && !entry.getValue().path("revision_id").asString().equals(revision)) {
            continue;
          }
          if (!ids.isEmpty() && !ids.contains(entry.getKey())) {
            continue;
          }
          if (result.size() >= body.path("limit").asInt()) {
            break;
          }
          var projected = new LinkedHashMap<String, Object>();
          for (String field : fields) {
            projected.put(field, entry.getValue().path(field));
          }
          if (failureMode.equals("changed-text") && fields.contains("text")) {
            projected.put("text", "changed synthetic text");
          }
          result.add(projected);
        }
        if (failureMode.equals("missing-verification") && !result.isEmpty()) {
          result.removeLast();
        }
        data = result;
      } else {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      if (responseDelayMillis > 0) {
        Thread.sleep(responseDelayMillis);
      }
      byte[] response =
          JSON.writeValueAsBytes(model ? Map.of("data", data) : Map.of("code", 0, "data", data));
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private List<Map<String, Object>> search(JsonNode request, Map<String, JsonNode> targetRows) {
    String filter = request.path("filter").asString();
    if (!filter.startsWith("workspace_id == \"org-main\" && (")) {
      return List.of();
    }
    var scope = new LinkedHashMap<String, String>();
    var matcher = SCOPED_GENERATION.matcher(filter);
    while (matcher.find()) {
      scope.put(matcher.group(1), matcher.group(2));
    }
    return targetRows.values().stream()
        .filter(
            row ->
                row.path("workspace_id").asString().equals("org-main")
                    && row.path("revision_id")
                        .asString()
                        .equals(scope.get(row.path("document_id").asString())))
        .sorted(java.util.Comparator.comparing(row -> row.path("id").asString()))
        .limit(request.path("limit").asInt())
        .map(
            row ->
                Map.<String, Object>of(
                    "id",
                    row.path("id").asString(),
                    "workspace_id",
                    row.path("workspace_id").asString(),
                    "document_id",
                    row.path("document_id").asString(),
                    "revision_id",
                    row.path("revision_id").asString(),
                    "distance",
                    0.9))
        .toList();
  }

  private static List<Map<String, Object>> createdIndex(JsonNode created, String name) {
    for (var index : created.path("indexParams")) {
      if (index.path("indexName").asString().equals(name)) {
        return List.of(
            Map.of(
                "indexName",
                name,
                "fieldName",
                index.path("fieldName").asString(),
                "indexType",
                index.path("indexType").asString(),
                "metricType",
                index.path("metricType").asString(),
                "indexState",
                "Finished"));
      }
    }
    return List.of();
  }

  private static Map<String, Object> createdDescription(JsonNode created) {
    var schema = created.path("schema");
    var fields = new ArrayList<Map<String, Object>>();
    for (var field : schema.path("fields")) {
      var description = new LinkedHashMap<String, Object>();
      description.put("name", field.path("fieldName").asString());
      description.put("type", field.path("dataType").asString());
      description.put("primaryKey", field.path("isPrimary").asBoolean(false));
      description.put("autoId", schema.path("autoID").asBoolean());
      description.put("nullable", field.path("nullable").asBoolean(false));
      var params = new ArrayList<Map<String, String>>();
      for (var entry : field.path("elementTypeParams").properties()) {
        params.add(
            Map.of(
                "key",
                entry.getKey(),
                "value",
                entry.getValue().isString()
                    ? entry.getValue().asString()
                    : entry.getValue().toString()));
      }
      description.put("params", params);
      boolean output = false;
      for (var function : schema.path("functions")) {
        for (var name : function.path("outputFieldNames")) {
          output |= name.asString().equals(field.path("fieldName").asString());
        }
      }
      if (output) description.put("isFunctionOutput", true);
      fields.add(description);
    }
    var functions = new ArrayList<Map<String, Object>>();
    for (var function : schema.path("functions")) {
      functions.add(
          Map.of(
              "name",
              function.path("name").asString(),
              "type",
              function.path("type").asString(),
              "inputFieldNames",
              function.path("inputFieldNames"),
              "outputFieldNames",
              function.path("outputFieldNames"),
              "params",
              List.of()));
    }
    return Map.of(
        "collectionName",
        created.path("collectionName").asString(),
        "description",
        created.path("description").asString(),
        "consistencyLevel",
        created.path("params").path("consistencyLevel").asString(),
        "autoId",
        schema.path("autoID").asBoolean(),
        "enableDynamicField",
        schema.path("enableDynamicField").asBoolean(),
        "fields",
        fields,
        "functions",
        functions);
  }

  private Map<String, Object> description() {
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
            List.of(Map.of("key", "dim", "value", Integer.toString(dimensions)))));
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
        settings().projection().collection(),
        "description",
        "evidence-rag-java-text-v1;workspace=org-main;embedding="
            + settings().projection().embeddingIdentity()
            + ";dim="
            + dimensions,
        "consistencyLevel",
        "Strong",
        "autoId",
        false,
        "enableDynamicField",
        false,
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
    releaseEmbedding.countDown();
    releaseUpsert.countDown();
    server.stop(0);
    executor.shutdownNow();
  }
}
