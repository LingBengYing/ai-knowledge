package com.evidence.rag.retrieval;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit Milvus REST v2 Adapter; no Spring bean, lifecycle hook, or collection autodiscovery. */
public final class MilvusRestProjection implements RetrievalProjection, AutoCloseable {
  private static final int MAX_REQUEST_BYTES = 4 * 1_048_576;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(MAX_REQUEST_BYTES)
                          .maxNumberLength(128)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public record Settings(
      URI endpoint,
      String token,
      String database,
      String collection,
      String workspaceId,
      String embeddingIdentity,
      int dimension,
      Duration timeout,
      int maxResponseBytes,
      boolean allowLoopbackHttp) {
    public Settings {
      boolean loopback =
          endpoint != null
              && endpoint.getHost() != null
              && List.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost());
      if (endpoint == null
          || endpoint.getHost() == null
          || endpoint.getUserInfo() != null
          || endpoint.getQuery() != null
          || endpoint.getFragment() != null
          || (endpoint.getPort() != -1 && (endpoint.getPort() < 1 || endpoint.getPort() > 65535))
          || (!endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/"))
          || !("https".equals(endpoint.getScheme())
              || (allowLoopbackHttp && loopback && "http".equals(endpoint.getScheme())))
          || token == null
          || !token.matches("[!-~]{0,4096}")
          || database == null
          || !database.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
          || collection == null
          || !collection.matches("java_[A-Za-z0-9_]{1,122}")
          || workspaceId == null
          || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || embeddingIdentity == null
          || !embeddingIdentity.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,159}")
          || dimension < 2
          || dimension > 32_768
          || timeout == null
          || timeout.compareTo(Duration.ofMillis(1)) < 0
          || timeout.compareTo(Duration.ofSeconds(60)) > 0
          || maxResponseBytes < 1024
          || maxResponseBytes > MAX_REQUEST_BYTES) {
        throw new Failure("projection_invalid_configuration");
      }
    }

    @Override
    public String toString() {
      return "Settings[redacted]";
    }
  }

  private final Settings settings;
  private final HttpClient client;
  private boolean initialized;

  public MilvusRestProjection(Settings settings) {
    if (settings == null) {
      throw new Failure("projection_invalid_configuration");
    }
    this.settings = settings;
    client =
        HttpClient.newBuilder()
            .connectTimeout(settings.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public synchronized void initialize() {
    initialized = false;
    long deadline = deadline();
    JsonNode has = post("collections/has", base(), deadline).path("has");
    if (!has.isBoolean()) {
      throw new Failure("projection_invalid_response");
    }
    if (!has.booleanValue()) {
      post("collections/create", createRequest(), deadline);
    }
    validateSchema(post("collections/describe", base(), deadline));
    validateIndex("dense", "dense_index", "FLAT", "COSINE", deadline);
    validateIndex("sparse", "sparse_index", "SPARSE_INVERTED_INDEX", "BM25", deadline);
    post("collections/load", base(), deadline);
    initialized = true;
  }

  @Override
  public synchronized void upsert(List<Entry> entries) {
    if (entries == null || entries.size() > MAX_BATCH) {
      throw new Failure("projection_invalid_input");
    }
    var rows = new ArrayList<Map<String, Object>>();
    var expectedIds = new HashSet<String>();
    for (Entry entry : entries) {
      if (entry == null
          || !settings.workspaceId().equals(entry.workspaceId())
          || entry.vector().size() != settings.dimension()
          || !expectedIds.add(entry.segmentId())) {
        throw new Failure("projection_invalid_input");
      }
      rows.add(
          Map.of(
              "id",
              entry.segmentId(),
              "workspace_id",
              entry.workspaceId(),
              "document_id",
              entry.documentId(),
              "revision_id",
              entry.revisionId(),
              "text",
              entry.text(),
              "dense",
              entry.vector()));
    }
    if (rows.isEmpty()) {
      return;
    }
    requireInitialized();
    var request = base();
    request.put("data", rows);
    JsonNode receipt = post("entities/upsert", request, deadline());
    responseCheck(
        receipt.isObject()
            && receipt.path("upsertCount").isIntegralNumber()
            && receipt.path("upsertCount").toString().equals(Integer.toString(rows.size())));
    JsonNode ids = receipt.path("upsertIds");
    responseCheck(ids.isArray() && ids.size() == rows.size());
    var received = new HashSet<String>();
    for (JsonNode id : ids) {
      responseCheck(
          id.isString()
              && expectedIds.contains(id.stringValue())
              && received.add(id.stringValue()));
    }
  }

  @Override
  public synchronized List<Candidate> search(Query query) {
    if (query == null
        || !settings.workspaceId().equals(query.scope().workspaceId())
        || query.vector().size() != settings.dimension()) {
      throw new Failure("projection_invalid_input");
    }
    if (query.scope().documentRevisions().isEmpty()) {
      return List.of();
    }
    requireInitialized();
    long deadline = deadline();
    String filter = filter(query.scope());
    var identities = new HashMap<String, String>();
    List<Hit> dense =
        searchRoute(
            "dense", List.of(query.vector()), "COSINE", query, filter, identities, deadline);
    List<Hit> sparse =
        searchRoute("sparse", List.of(query.text()), "BM25", query, filter, identities, deadline);
    var scores = new HashMap<String, Double>();
    for (List<Hit> route : List.of(dense, sparse)) {
      for (int index = 0; index < route.size(); index++) {
        scores.merge(route.get(index).id(), 1.0 / (61 + index), Double::sum);
      }
    }
    return scores.entrySet().stream()
        .map(item -> new Candidate(item.getKey(), item.getValue()))
        .sorted(
            Comparator.comparingDouble(Candidate::score)
                .reversed()
                .thenComparing(Candidate::segmentId))
        .limit(query.limit())
        .toList();
  }

  private void requireInitialized() {
    if (!initialized) {
      throw new Failure("projection_not_initialized");
    }
  }

  private static String filter(AuthorizedScope scope) {
    var clauses = new ArrayList<String>();
    scope
        .documentRevisions()
        .forEach(
            (document, revision) ->
                clauses.add(
                    "(document_id == \""
                        + document
                        + "\" && revision_id == \""
                        + revision
                        + "\")"));
    return "workspace_id == \""
        + scope.workspaceId()
        + "\" && ("
        + String.join(" || ", clauses)
        + ")";
  }

  private List<Hit> searchRoute(
      String field,
      List<?> data,
      String metric,
      Query query,
      String filter,
      Map<String, String> identities,
      long deadline) {
    var request = base();
    request.put("annsField", field);
    request.put("data", data);
    request.put("filter", filter);
    request.put("limit", query.limit());
    request.put("outputFields", List.of("id", "workspace_id", "document_id", "revision_id"));
    request.put("consistencyLevel", "Strong");
    request.put("searchParams", Map.of("metricType", metric, "params", Map.of()));
    JsonNode response = post("entities/search", request, deadline);
    responseCheck(response.isArray() && response.size() <= query.limit());
    var hits = new ArrayList<Hit>();
    for (JsonNode row : response) {
      String id = responseText(row, "id");
      String workspace = responseText(row, "workspace_id");
      String document = responseText(row, "document_id");
      String revision = responseText(row, "revision_id");
      responseCheck(
          id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
              && settings.workspaceId().equals(workspace)
              && revision.equals(query.scope().documentRevisions().get(document)));
      String identity = document + "/" + revision;
      String previous = identities.putIfAbsent(id, identity);
      responseCheck(previous == null || previous.equals(identity));
      JsonNode score = row.path("distance");
      responseCheck(score.isNumber() && Double.isFinite(score.doubleValue()));
      hits.add(new Hit(id, score.doubleValue()));
    }
    hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id));
    var unique = new LinkedHashMap<String, Hit>();
    hits.forEach(hit -> unique.putIfAbsent(hit.id(), hit));
    return List.copyOf(unique.values());
  }

  private record Hit(String id, double score) {}

  private static String responseText(JsonNode row, String field) {
    responseCheck(row.isObject() && row.path(field).isString());
    return row.path(field).stringValue();
  }

  private static void responseCheck(boolean valid) {
    if (!valid) {
      throw new Failure("projection_invalid_response");
    }
  }

  private Map<String, Object> base() {
    var body = new HashMap<String, Object>();
    body.put("dbName", settings.database());
    body.put("collectionName", settings.collection());
    return body;
  }

  private String marker() {
    return "evidence-rag-java-text-v1;workspace="
        + settings.workspaceId()
        + ";embedding="
        + settings.embeddingIdentity()
        + ";dim="
        + settings.dimension();
  }

  private Map<String, Object> createRequest() {
    var fields = new ArrayList<Map<String, Object>>();
    for (String name : List.of("id", "workspace_id", "document_id", "revision_id", "text")) {
      var parameters = new HashMap<String, Object>();
      parameters.put("max_length", name.equals("text") ? MAX_TEXT_BYTES : 128);
      if (name.equals("text")) {
        parameters.put("enable_analyzer", true);
        parameters.put("analyzer_params", Map.of("type", "standard"));
      }
      fields.add(
          Map.of(
              "fieldName",
              name,
              "dataType",
              "VarChar",
              "isPrimary",
              name.equals("id"),
              "elementTypeParams",
              parameters));
    }
    fields.add(
        Map.of(
            "fieldName",
            "dense",
            "dataType",
            "FloatVector",
            "elementTypeParams",
            Map.of("dim", settings.dimension())));
    fields.add(Map.of("fieldName", "sparse", "dataType", "SparseFloatVector"));
    var body = base();
    body.put("description", marker());
    body.put(
        "schema",
        Map.of(
            "autoID",
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
                    "BM25",
                    "inputFieldNames",
                    List.of("text"),
                    "outputFieldNames",
                    List.of("sparse"),
                    "params",
                    Map.of()))));
    body.put(
        "indexParams",
        List.of(
            Map.of(
                "fieldName",
                "dense",
                "indexName",
                "dense_index",
                "indexType",
                "FLAT",
                "metricType",
                "COSINE"),
            Map.of(
                "fieldName",
                "sparse",
                "indexName",
                "sparse_index",
                "indexType",
                "SPARSE_INVERTED_INDEX",
                "metricType",
                "BM25")));
    body.put("params", Map.of("consistencyLevel", "Strong"));
    return body;
  }

  private void validateSchema(JsonNode schema) {
    check(text(schema, "collectionName").equals(settings.collection()));
    check(text(schema, "description").equals(marker()));
    checkBoolean(schema, "autoId", false);
    checkBoolean(schema, "enableDynamicField", false);
    JsonNode fields = schema.path("fields");
    check(fields.isArray() && fields.size() == 7);
    var byName = new HashMap<String, JsonNode>();
    for (JsonNode field : fields) {
      check(byName.put(text(field, "name"), field) == null);
    }
    for (String name :
        List.of("id", "workspace_id", "document_id", "revision_id", "text", "dense", "sparse")) {
      JsonNode field = byName.get(name);
      check(field != null);
      checkBoolean(field, "primaryKey", name.equals("id"));
      checkBoolean(field, "autoId", false);
      checkBoolean(field, "nullable", false);
      String expectedType =
          name.equals("dense")
              ? "FloatVector"
              : name.equals("sparse") ? "SparseFloatVector" : "VarChar";
      check(text(field, "type").equals(expectedType));
      Map<String, String> parameters = parameters(field.path("params"));
      if (name.equals("dense")) {
        check(Integer.toString(settings.dimension()).equals(parameters.get("dim")));
      } else if (name.equals("sparse")) {
        checkBoolean(field, "isFunctionOutput", true);
      } else {
        check(
            Integer.toString(name.equals("text") ? MAX_TEXT_BYTES : 128)
                .equals(parameters.get("max_length")));
      }
      if (name.equals("text")) {
        check("true".equals(parameters.get("enable_analyzer")));
        try {
          check(
              JSON.readTree(parameters.getOrDefault("analyzer_params", "null"))
                  .equals(JSON.valueToTree(Map.of("type", "standard"))));
        } catch (RuntimeException ignored) {
          throw new Failure("projection_schema_mismatch");
        }
      }
    }
    JsonNode functions = schema.path("functions");
    check(functions.isArray() && functions.size() == 1);
    JsonNode bm25 = functions.get(0);
    check(text(bm25, "name").equals("text_bm25"));
    JsonNode type = bm25.path("type");
    check(
        (type.isIntegralNumber() && type.toString().equals("1"))
            || (type.isString() && type.stringValue().equals("BM25")));
    check(bm25.path("inputFieldNames").equals(JSON.valueToTree(List.of("text"))));
    check(bm25.path("outputFieldNames").equals(JSON.valueToTree(List.of("sparse"))));
    check(parameters(bm25.path("params")).isEmpty());
  }

  private void validateIndex(String field, String name, String type, String metric, long deadline) {
    var request = base();
    request.put("indexName", name);
    JsonNode indexes = post("indexes/describe", request, deadline);
    check(indexes.isArray() && indexes.size() == 1);
    JsonNode index = indexes.get(0);
    check(
        text(index, "indexName").equals(name)
            && text(index, "fieldName").equals(field)
            && text(index, "indexType").equals(type)
            && text(index, "metricType").equals(metric)
            && text(index, "indexState").equals("Finished"));
  }

  private static String text(JsonNode object, String field) {
    check(object != null && object.path(field).isString());
    return object.path(field).stringValue();
  }

  private static void checkBoolean(JsonNode object, String field, boolean expected) {
    check(object.path(field).isBoolean() && object.path(field).booleanValue() == expected);
  }

  private static Map<String, String> parameters(JsonNode node) {
    var result = new HashMap<String, String>();
    if (node.isMissingNode() || node.isNull()) {
      return result;
    }
    check(node.isArray());
    for (JsonNode pair : node) {
      check(result.put(text(pair, "key"), text(pair, "value")) == null);
    }
    return result;
  }

  private static void check(boolean valid) {
    if (!valid) {
      throw new Failure("projection_schema_mismatch");
    }
  }

  private long deadline() {
    return System.nanoTime() + settings.timeout().toNanos();
  }

  private JsonNode post(String path, Map<String, Object> body, long deadline) {
    if (Thread.currentThread().isInterrupted()) {
      throw new Failure("projection_interrupted");
    }
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      if (bytes.length > MAX_REQUEST_BYTES) {
        throw new Failure("projection_request_too_large");
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new Failure("projection_timeout");
      }
      var builder =
          HttpRequest.newBuilder(settings.endpoint().resolve("/v2/vectordb/" + path))
              .timeout(Duration.ofNanos(remaining))
              .header("Content-Type", "application/json")
              .header(
                  "Request-Timeout",
                  Long.toString(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining))))
              .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
      if (!settings.token().isEmpty()) {
        builder.header("Authorization", "Bearer " + settings.token());
      }
      pending =
          client.sendAsync(
              builder.build(), ignored -> new BoundedBody(settings.maxResponseBytes()));
      HttpResponse<byte[]> response =
          pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (response.statusCode() != 200) {
        throw new Failure("projection_remote_failed");
      }
      JsonNode parsed = JSON.readTree(response.body());
      if (Thread.currentThread().isInterrupted()) {
        throw new Failure("projection_interrupted");
      }
      if (deadline - System.nanoTime() <= 0) {
        throw new Failure("projection_timeout");
      }
      if (parsed == null
          || !parsed.isObject()
          || !parsed.path("code").isIntegralNumber()
          || !parsed.path("code").toString().equals("0")
          || !parsed.has("data")) {
        throw new Failure("projection_invalid_response");
      }
      return parsed.path("data");
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new Failure("projection_interrupted");
    } catch (TimeoutException ignored) {
      throw new Failure("projection_timeout");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof Failure failure) {
        throw failure;
      }
      throw new Failure("projection_transport_failed");
    } catch (Failure failure) {
      throw failure;
    } catch (RuntimeException ignored) {
      throw new Failure("projection_invalid_response");
    } finally {
      if (pending != null && !pending.isDone()) {
        pending.cancel(true);
      }
    }
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBody(int maximum) {
      this.maximum = maximum;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximum - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new Failure("projection_response_too_large"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable ignored) {
      result.completeExceptionally(new Failure("projection_transport_failed"));
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
