package com.evidence.rag.client.vector;

import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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
        throw new ProjectionException("projection_invalid_configuration");
      }
    }

    @Override
    public String toString() {
      return "Settings[redacted]";
    }

    public String identity() {
      try {
        // The schema contract is versioned independently from secret and operational budgets.
        String target =
            String.join(
                "\n",
                "evidence-rag-milvus-projection-v1",
                endpoint.resolve("/").toASCIIString(),
                database,
                collection,
                "schema-v1:strong:no-dynamic:id128:text16384:standard-bm25:float32-flat-cosine:sparse-bm25",
                workspaceId,
                embeddingIdentity,
                Integer.toString(dimension));
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(target.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 unavailable");
      }
    }
  }

  private final Settings settings;
  private final MilvusSchema schema;
  private final HttpClient client;
  private final ReentrantLock operationLock = new ReentrantLock();
  private boolean initialized;

  public MilvusRestProjection(Settings settings) {
    if (settings == null) {
      throw new ProjectionException("projection_invalid_configuration");
    }
    this.settings = settings;
    this.schema = new MilvusSchema(settings, JSON);
    client =
        HttpClient.newBuilder()
            .connectTimeout(settings.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public String identity() {
    return settings.identity();
  }

  /**
   * Deletes only a qualified document's logical rows. No collection creation, compaction, GC or
   * remote-write termination is implied by this operation.
   */
  public void deleteDocument(String workspaceId, String documentId, List<String> generations) {
    if (!settings.workspaceId().equals(workspaceId)
        || documentId == null
        || !documentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
        || generations == null
        || generations.size() > 10_000
        || generations.stream()
            .anyMatch(g -> g == null || !g.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
      throw new ProjectionException("projection_invalid_input");
    }
    long until = deadline();
    withinOperation(
        until,
        () -> {
          JsonNode has = post("collections/has", base(), until).path("has");
          responseCheck(has.isBoolean());
          if (!has.booleanValue()) {
            return null;
          }
          schema.validate(post("collections/describe", base(), until));
          for (String generation : new HashSet<>(generations)) {
            JsonNode rows =
                post(
                    "entities/query",
                    queryRequest("revision_id == \"" + generation + "\"", 1, false),
                    until);
            responseCheck(rows.isArray() && rows.size() <= 1);
            for (JsonNode row : rows) {
              responseCheck(
                  workspaceId.equals(responseText(row, "workspace_id"))
                      && documentId.equals(responseText(row, "document_id"))
                      && generation.equals(responseText(row, "revision_id")));
            }
          }
          String filter =
              "workspace_id == \"" + workspaceId + "\" && document_id == \"" + documentId + "\"";
          var deletion = base();
          deletion.put("filter", filter);
          // REST v2 accepts code=0/data={}; deleteCount is not a required response member.
          responseCheck(post("entities/delete", deletion, until).isObject());
          JsonNode remaining = post("entities/query", queryRequest(filter, 1, false), until);
          responseCheck(remaining.isArray() && remaining.isEmpty());
          for (String generation : new HashSet<>(generations)) {
            JsonNode rows =
                post(
                    "entities/query",
                    queryRequest("revision_id == \"" + generation + "\"", 1, false),
                    until);
            responseCheck(rows.isArray() && rows.isEmpty());
          }
          return null;
        });
  }

  @Override
  public VerifiedRevision verify(RevisionManifest manifest) {
    long deadline = deadline();
    return withinOperation(deadline, () -> verify(manifest, deadline));
  }

  private VerifiedRevision verify(RevisionManifest manifest, long deadline) {
    if (manifest == null || !settings.workspaceId().equals(manifest.workspaceId())) {
      throw new ProjectionException("projection_invalid_input");
    }
    requireInitialized();
    schema.validate(post("collections/describe", base(), deadline));
    validateIndex("dense", "dense_index", "FLAT", "COSINE", deadline);
    validateIndex("sparse", "sparse_index", "SPARSE_INVERTED_INDEX", "BM25", deadline);
    verifyMetadata(manifest, deadline);
    var ids = new ArrayList<>(manifest.entryDigests().keySet());
    // Includes worst-case text JSON escaping and generous finite-float/scalar metadata overhead.
    int maximumRowBytes = MAX_TEXT_BYTES * 6 + settings.dimension() * 32 + 2048;
    int batchSize =
        Math.min(
            16,
            (Math.min(MAX_REQUEST_BYTES, settings.maxResponseBytes()) - 1024) / maximumRowBytes);
    if (batchSize < 1) {
      throw new ProjectionException("projection_response_budget_insufficient");
    }
    for (int offset = 0; offset < ids.size(); offset += batchSize) {
      checkBudget(deadline);
      List<String> batch = ids.subList(offset, Math.min(ids.size(), offset + batchSize));
      var request = queryRequest("id in " + JSON.writeValueAsString(batch), batch.size() + 1, true);
      JsonNode rows = post("entities/query", request, deadline);
      responseCheck(rows.isArray() && rows.size() == batch.size());
      var remaining = new HashSet<>(batch);
      for (JsonNode row : rows) {
        checkBudget(deadline);
        String id = validateMetadataRow(row, manifest);
        responseCheck(remaining.remove(id));
        JsonNode dense = row.path("dense");
        responseCheck(dense.isArray() && dense.size() == settings.dimension());
        var vector = new ArrayList<Double>(dense.size());
        for (JsonNode value : dense) {
          checkBudget(deadline);
          responseCheck(value.isNumber() && Double.isFinite(value.doubleValue()));
          vector.add(value.doubleValue());
        }
        Entry entry;
        try {
          entry =
              new Entry(
                  id,
                  manifest.workspaceId(),
                  manifest.documentId(),
                  manifest.revisionId(),
                  responseText(row, "text"),
                  vector);
        } catch (ProjectionException failure) {
          throw new ProjectionException("projection_invalid_response");
        }
        responseCheck(
            RetrievalProjection.entryDigest(entry).equals(manifest.entryDigests().get(id)));
        checkBudget(deadline);
      }
      responseCheck(remaining.isEmpty());
    }
    // No multi-request snapshot is claimed: the authority/runtime must freeze this revision writer.
    verifyMetadata(manifest, deadline);
    validateIndex("dense", "dense_index", "FLAT", "COSINE", deadline);
    validateIndex("sparse", "sparse_index", "SPARSE_INVERTED_INDEX", "BM25", deadline);
    schema.validate(post("collections/describe", base(), deadline));
    var receipt = new VerifiedRevision(identity(), manifest.sha256(), ids.size());
    checkBudget(deadline);
    return receipt;
  }

  private void verifyMetadata(RevisionManifest manifest, long deadline) {
    // Revision-only filtering also detects unexpected rows in another workspace/document.
    var request =
        queryRequest(
            "revision_id == \"" + manifest.revisionId() + "\"",
            manifest.entryDigests().size() + 1,
            false);
    JsonNode rows = post("entities/query", request, deadline);
    responseCheck(rows.isArray() && rows.size() == manifest.entryDigests().size());
    Set<String> remaining = new HashSet<>(manifest.entryDigests().keySet());
    for (JsonNode row : rows) {
      checkBudget(deadline);
      responseCheck(remaining.remove(validateMetadataRow(row, manifest)));
    }
    responseCheck(remaining.isEmpty());
  }

  private String validateMetadataRow(JsonNode row, RevisionManifest manifest) {
    String id = responseText(row, "id");
    responseCheck(
        manifest.entryDigests().containsKey(id)
            && manifest.workspaceId().equals(responseText(row, "workspace_id"))
            && manifest.documentId().equals(responseText(row, "document_id"))
            && manifest.revisionId().equals(responseText(row, "revision_id")));
    return id;
  }

  private Map<String, Object> queryRequest(String filter, int limit, boolean content) {
    var request = base();
    request.put("filter", filter);
    request.put("limit", limit);
    request.put("offset", 0);
    request.put(
        "outputFields",
        content
            ? List.of("id", "workspace_id", "document_id", "revision_id", "text", "dense")
            : List.of("id", "workspace_id", "document_id", "revision_id"));
    // Query v2.6 does not document a request override; describe must prove collection Strong.
    return request;
  }

  @Override
  public void initialize() {
    long deadline = deadline();
    withinOperation(
        deadline,
        () -> {
          initialize(deadline);
          return null;
        });
  }

  @Override
  public void prepareSearch() {
    long deadline = deadline();
    withinOperation(
        deadline,
        () -> {
          initialized = false;
          JsonNode has = post("collections/has", base(), deadline).path("has");
          if (!has.isBoolean()) {
            throw new ProjectionException("projection_invalid_response");
          }
          if (!has.booleanValue()) {
            throw new ProjectionException("projection_not_initialized");
          }
          schema.validate(post("collections/describe", base(), deadline));
          validateIndex("dense", "dense_index", "FLAT", "COSINE", deadline);
          validateIndex("sparse", "sparse_index", "SPARSE_INVERTED_INDEX", "BM25", deadline);
          checkBudget(deadline);
          initialized = true;
          return null;
        });
  }

  private void initialize(long deadline) {
    initialized = false;
    JsonNode has = post("collections/has", base(), deadline).path("has");
    if (!has.isBoolean()) {
      throw new ProjectionException("projection_invalid_response");
    }
    if (!has.booleanValue()) {
      post("collections/create", schema.createRequest(), deadline);
    }
    schema.validate(post("collections/describe", base(), deadline));
    validateIndex("dense", "dense_index", "FLAT", "COSINE", deadline);
    validateIndex("sparse", "sparse_index", "SPARSE_INVERTED_INDEX", "BM25", deadline);
    post("collections/load", base(), deadline);
    initialized = true;
  }

  @Override
  public void upsert(List<Entry> entries) {
    long deadline = deadline();
    withinOperation(
        deadline,
        () -> {
          upsert(entries, deadline);
          return null;
        });
  }

  private void upsert(List<Entry> entries, long deadline) {
    if (entries == null || entries.size() > MAX_BATCH) {
      throw new ProjectionException("projection_invalid_input");
    }
    var rows = new ArrayList<Map<String, Object>>();
    var expectedIds = new HashSet<String>();
    for (Entry entry : entries) {
      checkBudget(deadline);
      if (entry == null
          || !settings.workspaceId().equals(entry.workspaceId())
          || entry.vector().size() != settings.dimension()
          || !expectedIds.add(entry.segmentId())) {
        throw new ProjectionException("projection_invalid_input");
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
    JsonNode receipt = post("entities/upsert", request, deadline);
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
  public List<Candidate> search(Query query) {
    long deadline = deadline();
    return withinOperation(deadline, () -> search(query, deadline));
  }

  private List<Candidate> search(Query query, long deadline) {
    if (query == null
        || !settings.workspaceId().equals(query.scope().workspaceId())
        || query.vector().size() != settings.dimension()) {
      throw new ProjectionException("projection_invalid_input");
    }
    if (query.scope().documentRevisions().isEmpty()) {
      return List.of();
    }
    requireInitialized();
    String filter = filter(query.scope());
    var identities = new HashMap<String, String>();
    List<Hit> dense =
        searchRoute(
            "dense", List.of(query.vector()), "COSINE", query, filter, identities, deadline);
    List<Hit> sparse =
        query.mode() == SearchMode.HYBRID
            ? searchRoute(
                "sparse", List.of(query.text()), "BM25", query, filter, identities, deadline)
            : List.of();
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
      throw new ProjectionException("projection_not_initialized");
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
      throw new ProjectionException("projection_invalid_response");
    }
  }

  private Map<String, Object> base() {
    var body = new HashMap<String, Object>();
    body.put("dbName", settings.database());
    body.put("collectionName", settings.collection());
    return body;
  }

  private void validateIndex(String field, String name, String type, String metric, long deadline) {
    var request = base();
    request.put("indexName", name);
    schema.validateIndex(post("indexes/describe", request, deadline), field, name, type, metric);
  }

  private long deadline() {
    return System.nanoTime() + settings.timeout().toNanos();
  }

  private <T> T withinOperation(long deadline, Supplier<T> action) {
    checkBudget(deadline);
    boolean locked = false;
    try {
      locked =
          operationLock.tryLock(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (!locked) {
        throw new ProjectionException("projection_timeout");
      }
      checkBudget(deadline);
      T result = action.get();
      checkBudget(deadline);
      return result;
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new ProjectionException("projection_interrupted");
    } finally {
      if (locked) {
        operationLock.unlock();
      }
    }
  }

  private static void checkBudget(long deadline) {
    if (Thread.currentThread().isInterrupted()) {
      throw new ProjectionException("projection_interrupted");
    }
    if (deadline - System.nanoTime() <= 0) {
      throw new ProjectionException("projection_timeout");
    }
  }

  private JsonNode post(String path, Map<String, Object> body, long deadline) {
    if (Thread.currentThread().isInterrupted()) {
      throw new ProjectionException("projection_interrupted");
    }
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      byte[] bytes = JSON.writeValueAsBytes(body);
      if (bytes.length > MAX_REQUEST_BYTES) {
        throw new ProjectionException("projection_request_too_large");
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new ProjectionException("projection_timeout");
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
        throw new ProjectionException("projection_remote_failed");
      }
      JsonNode parsed = JSON.readTree(response.body());
      if (Thread.currentThread().isInterrupted()) {
        throw new ProjectionException("projection_interrupted");
      }
      if (deadline - System.nanoTime() <= 0) {
        throw new ProjectionException("projection_timeout");
      }
      if (parsed == null
          || !parsed.isObject()
          || !parsed.path("code").isIntegralNumber()
          || !parsed.path("code").toString().equals("0")
          || !parsed.has("data")) {
        throw new ProjectionException("projection_invalid_response");
      }
      return parsed.path("data");
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new ProjectionException("projection_interrupted");
    } catch (TimeoutException ignored) {
      throw new ProjectionException("projection_timeout");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof ProjectionException failure) {
        throw failure;
      }
      if (exception.getCause() instanceof HttpTimeoutException) {
        throw new ProjectionException("projection_timeout");
      }
      throw new ProjectionException("projection_transport_failed");
    } catch (ProjectionException failure) {
      throw failure;
    } catch (RuntimeException ignored) {
      throw new ProjectionException("projection_invalid_response");
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
          result.completeExceptionally(new ProjectionException("projection_response_too_large"));
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
      result.completeExceptionally(new ProjectionException("projection_transport_failed"));
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
