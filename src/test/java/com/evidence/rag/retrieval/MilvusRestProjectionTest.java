package com.evidence.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.retrieval.RetrievalProjection.AuthorizedScope;
import com.evidence.rag.retrieval.RetrievalProjection.Entry;
import com.evidence.rag.retrieval.RetrievalProjection.Query;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MilvusRestProjectionTest {
  private static MilvusRestProjection.Settings settings(URI endpoint) {
    return new MilvusRestProjection.Settings(
        endpoint,
        "",
        "default",
        "java_contract_test",
        "org-main",
        "fixture/embed@v1",
        2,
        Duration.ofSeconds(2),
        1_048_576,
        true);
  }

  @Test
  void emptyAuthorizedScopeNeverInitializesOrCallsRemote() {
    RetrievalProjection projection =
        new MilvusRestProjection(settings(URI.create("http://127.0.0.1:1")));
    assertEquals(
        List.of(),
        projection.search(
            new Query("政策", List.of(1.0, 0.0), new AuthorizedScope("org-main", Map.of()), 10)));
    var failure =
        assertThrows(
            RetrievalProjection.Failure.class,
            () ->
                projection.search(
                    new Query(
                        "政策",
                        List.of(1.0, 0.0),
                        new AuthorizedScope("org-main", Map.of("doc-a", "revision-a")),
                        10)));
    assertEquals("projection_not_initialized", failure.code());
  }

  @Test
  void explicitInitializationCreatesOnlyIsolatedCompatibleCollectionAndIndexes() throws Exception {
    try (var stub = new MilvusStub(false)) {
      RetrievalProjection projection = new MilvusRestProjection(settings(stub.endpoint()));
      assertTrue(stub.requests.isEmpty());
      projection.initialize();
      var created = stub.requests.stream().filter(r -> r.path().endsWith("/create")).toList();
      assertEquals(1, created.size());
      JsonNode body = created.getFirst().body();
      assertEquals("java_contract_test", body.path("collectionName").asString());
      assertEquals("default", body.path("dbName").asString());
      assertEquals(
          "evidence-rag-java-text-v1;workspace=org-main;embedding=fixture/embed@v1;dim=2",
          body.path("description").asString());
      assertEquals(false, body.path("schema").path("autoID").asBoolean());
      assertEquals(false, body.path("schema").path("enableDynamicField").asBoolean());
      assertEquals(7, body.path("schema").path("fields").size());
      assertEquals("BM25", body.path("schema").path("functions").get(0).path("type").asString());
      assertEquals("BM25", body.path("indexParams").get(1).path("metricType").asString());
      assertTrue(stub.requests.stream().anyMatch(r -> r.path().endsWith("/load")));
      assertTrue(
          stub.requests.stream()
              .noneMatch(r -> r.path().contains("drop") || r.path().contains("alter")));
    }
  }

  @Test
  void scopedDenseAndBm25SearchFuseStableIdentityOnlyCandidates() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      var entries =
          List.of(
              entry("segment-a", "doc-a", "revision-a"),
              entry("segment-b", "doc-b", "revision-b"),
              entry("segment-c", "doc-b", "revision-b"));
      projection.upsert(entries);
      stub.dense =
          List.of(
              hit("segment-b", "doc-b", "revision-b", 0.8),
              hit("segment-a", "doc-a", "revision-a", 0.8),
              hit("segment-a", "doc-a", "revision-a", 0.7));
      stub.sparse =
          List.of(
              hit("segment-a", "doc-a", "revision-a", 3.0),
              hit("segment-c", "doc-b", "revision-b", 2.0));
      String question = "政策\" or document_id != \"x";
      var candidates =
          projection.search(
              new Query(
                  question,
                  List.of(1.0, 0.0),
                  new AuthorizedScope(
                      "org-main", Map.of("doc-b", "revision-b", "doc-a", "revision-a")),
                  3));
      assertEquals(
          List.of("segment-a", "segment-b", "segment-c"),
          candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList());
      assertEquals(0.03278688524590164, candidates.getFirst().score(), 1e-15);
      assertEquals(0.016129032258064516, candidates.get(1).score(), 1e-15);
      assertEquals(2, RetrievalProjection.Candidate.class.getRecordComponents().length);
      var searches = stub.requests.stream().filter(r -> r.path().endsWith("/search")).toList();
      assertEquals(2, searches.size());
      for (var request : searches) {
        assertEquals(
            "workspace_id == \"org-main\" && ((document_id == \"doc-a\" && revision_id == \"revision-a\") || (document_id == \"doc-b\" && revision_id == \"revision-b\"))",
            request.body().path("filter").asString());
        assertEquals(3, request.body().path("limit").asInt());
        assertEquals("Strong", request.body().path("consistencyLevel").asString());
        assertEquals(
            MilvusStub.JSON.valueToTree(
                List.of("id", "workspace_id", "document_id", "revision_id")),
            request.body().path("outputFields"));
      }
      assertEquals("dense", searches.getFirst().body().path("annsField").asString());
      assertEquals(question, searches.get(1).body().path("data").get(0).asString());
      var upsert =
          stub.requests.stream()
              .filter(r -> r.path().endsWith("/upsert"))
              .findFirst()
              .orElseThrow()
              .body();
      assertEquals(3, upsert.path("data").size());
      assertEquals("合成政策证据", upsert.path("data").get(0).path("text").asString());
      assertFalse(upsert.path("data").get(0).has("sparse"));
    }
  }

  private static Entry entry(String id, String document, String revision) {
    return new Entry(id, "org-main", document, revision, "合成政策证据", List.of(1.0, 0.0));
  }

  @Test
  void incompatibleExistingSchemaAndIndexesNeverTriggerRepairOrLoad() throws Exception {
    List<Consumer<ObjectNode>> mutations =
        List.of(
            schema -> schema.put("collectionName", "legacy_collection"),
            schema -> schema.put("description", "evidence-rag-java-text-v1;embedding=other;dim=2"),
            schema -> schema.put("autoId", true),
            schema -> schema.put("enableDynamicField", true),
            schema -> schema.remove("fields"),
            schema -> ((ArrayNode) schema.path("fields")).remove(6),
            schema -> ((ObjectNode) schema.path("fields").get(1)).put("name", "id"),
            schema -> ((ObjectNode) schema.path("fields").get(0)).put("primaryKey", false),
            schema -> ((ObjectNode) schema.path("fields").get(1)).put("autoId", true),
            schema -> ((ObjectNode) schema.path("fields").get(1)).put("nullable", true),
            schema -> ((ObjectNode) schema.path("fields").get(1)).put("type", "Int64"),
            schema ->
                ((ObjectNode) schema.path("fields").get(5).path("params").get(0)).put("value", "3"),
            schema -> ((ObjectNode) schema.path("fields").get(6)).put("isFunctionOutput", false),
            schema ->
                ((ObjectNode) schema.path("fields").get(4).path("params").get(0))
                    .put("value", "1024"),
            schema ->
                ((ObjectNode) schema.path("fields").get(4).path("params").get(1))
                    .put("value", "false"),
            schema ->
                ((ObjectNode) schema.path("fields").get(4).path("params").get(2))
                    .put("value", "{}"),
            schema ->
                ((ObjectNode) schema.path("fields").get(4).path("params").get(2))
                    .put("value", "not json"),
            schema ->
                ((ArrayNode) schema.path("fields").get(0).path("params"))
                    .add(MilvusStub.JSON.valueToTree(Map.of("key", "max_length", "value", "128"))),
            schema -> schema.set("functions", MilvusStub.JSON.valueToTree(List.of())),
            schema -> ((ObjectNode) schema.path("functions").get(0)).put("name", "other"),
            schema -> ((ObjectNode) schema.path("functions").get(0)).put("type", 2),
            schema ->
                ((ObjectNode) schema.path("functions").get(0))
                    .set("inputFieldNames", MilvusStub.JSON.valueToTree(List.of("id"))),
            schema ->
                ((ObjectNode) schema.path("functions").get(0))
                    .set("outputFieldNames", MilvusStub.JSON.valueToTree(List.of("dense"))),
            schema ->
                ((ObjectNode) schema.path("functions").get(0))
                    .set(
                        "params",
                        MilvusStub.JSON.valueToTree(
                            List.of(Map.of("key", "unexpected", "value", "true")))));
    for (Consumer<ObjectNode> mutation : mutations) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/collections/describe")) {
                mutation.accept((ObjectNode) response.path("data"));
              }
            };
        var failure = assertThrows(RetrievalProjection.Failure.class, projection::initialize);
        assertEquals("projection_schema_mismatch", failure.code());
        assertTrue(
            stub.requests.stream()
                .noneMatch(r -> r.path().matches(".*/(create|load|drop|alter)$")));
      }
    }
    for (String field :
        List.of("indexName", "fieldName", "indexType", "metricType", "indexState")) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/indexes/describe")) {
                ((ObjectNode) response.path("data").get(0)).put(field, "incompatible");
              }
            };
        assertThrows(RetrievalProjection.Failure.class, projection::initialize);
        assertTrue(
            stub.requests.stream()
                .noneMatch(r -> r.path().matches(".*/(create|load|drop|alter)$")));
      }
    }
  }

  @Test
  void completeMaximumScopeAppearsInBothRequestsWithoutTruncation() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      var scope = new HashMap<String, String>();
      for (int i = 0; i < 128; i++) {
        scope.put("doc-" + i, "revision-" + i);
      }
      assertEquals(
          List.of(),
          projection.search(
              new Query("policy", List.of(1.0, 0.0), new AuthorizedScope("org-main", scope), 100)));
      var searches = stub.requests.stream().filter(r -> r.path().endsWith("/search")).toList();
      assertEquals(2, searches.size());
      for (Request request : searches) {
        String filter = request.body().path("filter").asString();
        for (int i = 0; i < 128; i++) {
          assertTrue(
              filter.contains(
                  "(document_id == \"doc-" + i + "\" && revision_id == \"revision-" + i + "\")"));
        }
      }
    }
  }

  @Test
  void entireInvalidBatchFailsBeforeAnyWriteAndEmptyBatchDoesNothing() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.upsert(List.of());
      assertTrue(stub.requests.isEmpty());
      assertThrows(
          RetrievalProjection.Failure.class,
          () -> projection.upsert(List.of(entry("s", "d", "r"))));
      projection.initialize();
      int before = stub.requests.size();
      for (List<Entry> batch :
          Arrays.<List<Entry>>asList(
              null,
              Arrays.asList(entry("s", "d", "r"), null),
              Collections.nCopies(65, entry("s", "d", "r")),
              List.of(entry("s", "d", "r"), entry("s", "d", "r")),
              List.of(
                  entry("s", "d", "r"),
                  new Entry("other", "org-other", "d", "r", "synthetic", List.of(1.0, 0.0))),
              List.of(new Entry("s", "org-main", "d", "r", "synthetic", List.of(1.0, 0.0, 0.0))))) {
        assertThrows(RetrievalProjection.Failure.class, () -> projection.upsert(batch));
      }
      assertThrows(RetrievalProjection.Failure.class, () -> projection.search(null));
      assertThrows(
          RetrievalProjection.Failure.class,
          () ->
              projection.search(
                  new Query(
                      "q", List.of(1.0, 0.0), new AuthorizedScope("org-other", Map.of()), 1)));
      assertThrows(
          RetrievalProjection.Failure.class,
          () ->
              projection.search(
                  new Query(
                      "q", List.of(1.0, 0.0, 0.0), new AuthorizedScope("org-main", Map.of()), 1)));
      assertEquals(before, stub.requests.size());
    }
  }

  @Test
  void hostileSearchRowsCannotCrossAuthorizedScopeOrForgeIdentityAndScore() throws Exception {
    List<Consumer<ObjectNode>> mutations =
        List.of(
            row -> row.put("workspace_id", "org-other"),
            row -> row.put("document_id", "not-selected"),
            row -> row.put("revision_id", "stale"),
            row -> row.put("id", "invalid\"id"),
            row -> row.put("id", 12),
            row -> row.remove("workspace_id"),
            row -> row.remove("distance"),
            row -> row.put("distance", "0.9"),
            row -> row.put("distance", "NaN"));
    for (Consumer<ObjectNode> mutation : mutations) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        projection.initialize();
        stub.dense = List.of(hit("s", "d", "r", 0.9));
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/search")) {
                mutation.accept((ObjectNode) response.path("data").get(0));
              }
            };
        assertThrows(RetrievalProjection.Failure.class, () -> projection.search(query()));
      }
    }
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      stub.dense = Collections.nCopies(11, hit("s", "d", "r", 0.9));
      assertThrows(RetrievalProjection.Failure.class, () -> projection.search(query()));
      stub.dense = List.of(hit("s", "d", "r", 0.9));
      stub.sparse = List.of(hit("s", "other", "active", 0.9));
      assertThrows(
          RetrievalProjection.Failure.class,
          () ->
              projection.search(
                  new Query(
                      "q",
                      List.of(1.0, 0.0),
                      new AuthorizedScope("org-main", Map.of("d", "r", "other", "active")),
                      10)));
    }
  }

  private static Query query() {
    return new Query(
        "policy", List.of(1.0, 0.0), new AuthorizedScope("org-main", Map.of("d", "r")), 10);
  }

  @Test
  void ambiguousOrIncompleteUpsertAcknowledgmentFailsWithoutRetry() throws Exception {
    List<Consumer<ObjectNode>> mutations =
        List.of(
            receipt -> receipt.put("upsertCount", 0),
            receipt -> receipt.put("upsertCount", "2"),
            receipt -> receipt.remove("upsertIds"),
            receipt -> receipt.set("upsertIds", MilvusStub.JSON.valueToTree(List.of("s1"))),
            receipt -> receipt.set("upsertIds", MilvusStub.JSON.valueToTree(List.of("s1", "s1"))),
            receipt ->
                receipt.set("upsertIds", MilvusStub.JSON.valueToTree(List.of("s1", "unexpected"))),
            receipt -> receipt.set("upsertIds", MilvusStub.JSON.valueToTree(List.of("s1", 2))));
    for (Consumer<ObjectNode> mutation : mutations) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        projection.initialize();
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/upsert")) {
                mutation.accept((ObjectNode) response.path("data"));
              }
            };
        assertThrows(
            RetrievalProjection.Failure.class,
            () -> projection.upsert(List.of(entry("s1", "d", "r"), entry("s2", "d", "r"))));
        assertEquals(1, stub.requests.stream().filter(r -> r.path().endsWith("/upsert")).count());
      }
    }
  }

  @Test
  void authenticationRemainsInHeaderAndRedirectsAreNeverFollowed() throws Exception {
    try (var target = new MilvusStub(true);
        var stub = new MilvusStub(true)) {
      var configured =
          new MilvusRestProjection.Settings(
              stub.endpoint(),
              "synthetic-" + "credential",
              "default",
              "java_contract_test",
              "org-main",
              "fixture/embed@v1",
              2,
              Duration.ofSeconds(2),
              1024,
              true);
      try (var projection = new MilvusRestProjection(configured)) {
        stub.faultyPath = "/v2/vectordb/collections/has";
        stub.faultyStatus = 307;
        stub.faultyBody = "remote private text".getBytes(StandardCharsets.UTF_8);
        stub.location = target.endpoint() + "/v2/vectordb/collections/has";
        var failure = assertThrows(RetrievalProjection.Failure.class, projection::initialize);
        assertEquals("projection_remote_failed", failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private"));
        assertFalse(configured.toString().contains("credential"));
        assertEquals("Bearer synthetic-credential", stub.requests.getFirst().authorization());
        assertFalse(stub.requests.getFirst().body().toString().contains("credential"));
        assertTrue(target.requests.isEmpty());
      }
    }
  }

  @Test
  void malformedAndOverComplexJsonOrRemoteErrorsExposeNoResponseOrCause() throws Exception {
    for (String body :
        List.of(
            "not json with private text",
            "[]",
            "{\"code\":7,\"message\":\"private text\"}",
            "{\"code\":\"0\",\"data\":{}}",
            "{\"code\":0}",
            "{\"code\":0,\"data\":{\"has\":\"false\"}}",
            "{\"code\":0,\"data\":{\"has\":true},\"code\":1}",
            "{\"code\":0,\"data\":{\"has\":true}}{}",
            "{\"code\":18446744073709551616,\"data\":{\"has\":true}}",
            "{\"code\":0,\"data\":{\"has\":true},\"nested\":"
                + "[".repeat(40)
                + "0"
                + "]".repeat(40)
                + "}")) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        stub.faultyPath = "/v2/vectordb/collections/has";
        stub.faultyBody = body.getBytes(StandardCharsets.UTF_8);
        var failure = assertThrows(RetrievalProjection.Failure.class, projection::initialize);
        assertFalse(failure.toString().contains("private"));
        assertNull(failure.getCause());
        assertEquals(1, stub.requests.size());
      }
    }
  }

  @Test
  void responseBodyIsBoundedBeforeAllocationAndDeadlineIncludesDelayedBody() throws Exception {
    try (var stub = new MilvusStub(true)) {
      var configured =
          new MilvusRestProjection.Settings(
              stub.endpoint(),
              "",
              "default",
              "java_contract_test",
              "org-main",
              "fixture/embed@v1",
              2,
              Duration.ofMillis(200),
              1024,
              true);
      stub.faultyPath = "/v2/vectordb/collections/has";
      stub.faultyBody = "x".repeat(2048).getBytes(StandardCharsets.UTF_8);
      try (var projection = new MilvusRestProjection(configured)) {
        assertEquals(
            "projection_response_too_large",
            assertThrows(RetrievalProjection.Failure.class, projection::initialize).code());
      }
      stub.faultyBody = "{\"code\":0,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8);
      stub.bodyDelayMillis = 1500;
      try (var projection = new MilvusRestProjection(configured)) {
        long started = System.nanoTime();
        assertThrows(RetrievalProjection.Failure.class, projection::initialize);
        assertTrue(
            Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
      }
    }
  }

  @Test
  void interruptedCallCancelsResponseAndPreservesThreadInterruptStatus() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      stub.faultyPath = "/v2/vectordb/collections/has";
      stub.faultyBody = "{}".getBytes(StandardCharsets.UTF_8);
      stub.bodyDelayMillis = 1500;
      var failure = new AtomicReference<RetrievalProjection.Failure>();
      var interrupted = new AtomicBoolean();
      Thread thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      projection.initialize();
                    } catch (RetrievalProjection.Failure result) {
                      failure.set(result);
                      interrupted.set(Thread.currentThread().isInterrupted());
                    }
                  });
      assertTrue(stub.headersSent.await(1, TimeUnit.SECONDS));
      thread.interrupt();
      thread.join(1000);
      assertFalse(thread.isAlive());
      assertNotNull(failure.get());
      assertEquals("projection_interrupted", failure.get().code());
      assertTrue(interrupted.get());
    }
  }

  @Test
  void connectionFailureIsSanitizedAndDoesNotMarkInitialized() {
    try (var projection = new MilvusRestProjection(settings(URI.create("http://127.0.0.1:1")))) {
      var failure = assertThrows(RetrievalProjection.Failure.class, projection::initialize);
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("127.0.0.1"));
      assertEquals(
          "projection_not_initialized",
          assertThrows(RetrievalProjection.Failure.class, () -> projection.search(query())).code());
    }
  }

  @Test
  void collectionCannotBeReusedByChangingConfiguredWorkspace() throws Exception {
    try (var stub = new MilvusStub(true)) {
      var other =
          new MilvusRestProjection.Settings(
              stub.endpoint(),
              "",
              "default",
              "java_contract_test",
              "org-other",
              "fixture/embed@v1",
              2,
              Duration.ofSeconds(2),
              1_048_576,
              true);
      try (var projection = new MilvusRestProjection(other)) {
        assertEquals(
            "projection_schema_mismatch",
            assertThrows(RetrievalProjection.Failure.class, projection::initialize).code());
        assertTrue(
            stub.requests.stream()
                .noneMatch(r -> r.path().matches(".*/(create|load|drop|alter)$")));
      }
    }
  }

  @Test
  void preexistingCancellationTakesPriorityOverTransportAndNeverDispatchesWrites()
      throws Exception {
    try (var stub = new MilvusStub(true)) {
      var projection = new MilvusRestProjection(settings(stub.endpoint()));
      projection.initialize();
      int before = stub.requests.size();
      // A previously closed transport must not be consulted after caller cancellation either.
      projection.close();
      Thread.currentThread().interrupt();
      try {
        var failure =
            assertThrows(
                RetrievalProjection.Failure.class,
                () -> projection.upsert(List.of(entry("s", "d", "r"))));
        assertEquals("projection_interrupted", failure.code());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(before, stub.requests.size());
    }
  }

  @Test
  void preexistingCancellationOnLiveTransportKeepsFlagAndSendsZeroRequests() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      stub.faultyPath = "/v2/vectordb/collections/has";
      stub.faultyBody = "{\"code\":0,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8);
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "projection_interrupted",
            assertThrows(RetrievalProjection.Failure.class, projection::initialize).code());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertFalse(stub.headersSent.await(100, TimeUnit.MILLISECONDS));
      assertTrue(stub.requests.isEmpty());
    }
  }

  private static Map<String, Object> hit(
      String id, String document, String revision, double distance) {
    return Map.of(
        "id",
        id,
        "workspace_id",
        "org-main",
        "document_id",
        document,
        "revision_id",
        revision,
        "distance",
        distance,
        "text",
        "untrusted remote text must not become authority");
  }

  private record Request(String path, JsonNode body, String authorization) {}

  private static final class MilvusStub implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    boolean exists;
    List<Map<String, Object>> dense = List.of();
    List<Map<String, Object>> sparse = List.of();
    BiConsumer<Request, ObjectNode> mutate = (request, response) -> {};
    String faultyPath;
    byte[] faultyBody;
    int faultyStatus = 200;
    long bodyDelayMillis;
    String location;
    final CountDownLatch headersSent = new CountDownLatch(1);

    MilvusStub(boolean exists) throws IOException {
      this.exists = exists;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String path = exchange.getRequestURI().getPath();
        var request =
            new Request(path, body, exchange.getRequestHeaders().getFirst("Authorization"));
        requests.add(request);
        if (path.equals(faultyPath)) {
          if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
          }
          exchange.sendResponseHeaders(faultyStatus, 0);
          exchange.getResponseBody().flush();
          headersSent.countDown();
          if (bodyDelayMillis > 0) {
            try {
              Thread.sleep(bodyDelayMillis);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
              return;
            }
          }
          exchange.getResponseBody().write(faultyBody);
          return;
        }
        Object data;
        if (path.endsWith("/collections/has")) {
          data = Map.of("has", exists);
        } else if (path.endsWith("/collections/create")) {
          exists = true;
          data = Map.of();
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
        } else if (path.endsWith("/collections/load")) {
          data = Map.of();
        } else if (path.endsWith("/entities/upsert")) {
          var ids = new ArrayList<String>();
          body.path("data").forEach(row -> ids.add(row.path("id").asString()));
          data = Map.of("upsertCount", ids.size(), "upsertIds", ids);
        } else if (path.endsWith("/entities/search")) {
          data = body.path("annsField").asString().equals("dense") ? dense : sparse;
        } else {
          exchange.sendResponseHeaders(404, -1);
          return;
        }
        ObjectNode responseTree = JSON.valueToTree(Map.of("code", 0, "data", data));
        mutate.accept(request, responseTree);
        byte[] response = JSON.writeValueAsBytes(responseTree);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      }
    }

    private static Map<String, Object> description() {
      List<Object> fields = new ArrayList<>();
      for (String name : List.of("id", "workspace_id", "document_id", "revision_id", "text")) {
        var params =
            new ArrayList<>(
                List.of(
                    Map.of("key", "max_length", "value", name.equals("text") ? "16384" : "128")));
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
          "java_contract_test",
          "description",
          "evidence-rag-java-text-v1;workspace=org-main;embedding=fixture/embed@v1;dim=2",
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
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
