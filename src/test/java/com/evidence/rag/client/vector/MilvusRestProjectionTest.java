package com.evidence.rag.client.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection.AuthorizedScope;
import com.evidence.rag.client.vector.RetrievalProjection.Entry;
import com.evidence.rag.client.vector.RetrievalProjection.Query;
import com.evidence.rag.client.vector.RetrievalProjection.RevisionManifest;
import com.evidence.rag.exception.ProjectionException;
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
import java.util.TreeMap;
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
  @Test
  void configuredFullTextUsesOnlyBm25AndDoesNotRequireAnEmbedding() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.prepareSearch();
      stub.dense = List.of(hit("dense-only", "d", "r", 0.99));
      stub.sparse = List.of(hit("keyword", "d", "r", 12.5));
      var result =
          projection.search(
              new Query(
                  "灯塔",
                  List.of(),
                  new AuthorizedScope("org-main", Map.of("d", "r")),
                  5,
                  RetrievalProjection.SearchMode.SPARSE_ONLY,
                  RetrievalProjection.FusionMode.WEIGHTED,
                  0.5));
      assertEquals(List.of(new RetrievalProjection.Candidate("keyword", 12.5)), result);
      var calls = stub.requests.stream().filter(r -> r.path().endsWith("/search")).toList();
      assertEquals(1, calls.size());
      assertEquals("sparse", calls.getFirst().body().path("annsField").asString());
      assertEquals("灯塔", calls.getFirst().body().path("data").get(0).asString());
    }
  }

  @Test
  void configuredVectorReturnsNormalizedCosineRatherThanRankOnlyScore() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.prepareSearch();
      stub.dense = List.of(hit("first", "d", "r", 0.8), hit("second", "d", "r", -0.5));
      var result =
          projection.search(
              new Query(
                  "灯塔",
                  List.of(1.0, 0.0),
                  new AuthorizedScope("org-main", Map.of("d", "r")),
                  5,
                  RetrievalProjection.SearchMode.DENSE_ONLY,
                  RetrievalProjection.FusionMode.WEIGHTED,
                  0.5));
      assertEquals(0.9, result.getFirst().score(), 1e-12);
      assertEquals(0.25, result.getLast().score(), 1e-12);
      assertEquals(1, stub.requests.stream().filter(r -> r.path().endsWith("/search")).count());
    }
  }

  @Test
  void configuredHybridWeightsChangeActualOrderAndUseNormalizedScores() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.prepareSearch();
      stub.dense = List.of(hit("semantic", "d", "r", 0.8), hit("keyword", "d", "r", -0.6));
      stub.sparse = List.of(hit("keyword", "d", "r", 10), hit("semantic", "d", "r", 0.1));
      var scope = new AuthorizedScope("org-main", Map.of("d", "r"));
      var semantic =
          projection.search(
              new Query(
                  "灯塔",
                  List.of(1.0, 0.0),
                  scope,
                  5,
                  RetrievalProjection.SearchMode.HYBRID,
                  RetrievalProjection.FusionMode.WEIGHTED,
                  0.9));
      var keyword =
          projection.search(
              new Query(
                  "灯塔",
                  List.of(1.0, 0.0),
                  scope,
                  5,
                  RetrievalProjection.SearchMode.HYBRID,
                  RetrievalProjection.FusionMode.WEIGHTED,
                  0.1));
      assertEquals("semantic", semantic.getFirst().segmentId());
      assertEquals(
          0.9 * 0.9 + 0.1 * 2 * Math.atan(0.1) / Math.PI, semantic.getFirst().score(), 1e-12);
      assertEquals("keyword", keyword.getFirst().segmentId());
      assertEquals(
          0.1 * 0.2 + 0.9 * 2 * Math.atan(10) / Math.PI, keyword.getFirst().score(), 1e-12);
    }
  }

  @Test
  void zeroWeightDoesNotAdmitCandidatesFromOnlyTheDisabledRoute() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.prepareSearch();
      stub.dense = List.of(hit("semantic", "d", "r", 0.8));
      stub.sparse = List.of(hit("keyword", "d", "r", 10));
      var scope = new AuthorizedScope("org-main", Map.of("d", "r"));
      assertEquals(
          List.of("keyword"),
          projection
              .search(
                  new Query(
                      "灯塔",
                      List.of(1.0, 0.0),
                      scope,
                      5,
                      RetrievalProjection.SearchMode.HYBRID,
                      RetrievalProjection.FusionMode.WEIGHTED,
                      0))
              .stream()
              .map(RetrievalProjection.Candidate::segmentId)
              .toList());
      assertEquals(
          List.of("semantic"),
          projection
              .search(
                  new Query(
                      "灯塔",
                      List.of(1.0, 0.0),
                      scope,
                      5,
                      RetrievalProjection.SearchMode.HYBRID,
                      RetrievalProjection.FusionMode.WEIGHTED,
                      1))
              .stream()
              .map(RetrievalProjection.Candidate::segmentId)
              .toList());
    }
  }

  @Test
  void fullWorkspaceIsBatchedWithoutTruncationAndRoutesAreGloballyRanked() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      var scope = new TreeMap<String, String>();
      for (int i = 0; i < 129; i++) scope.put(String.format("d%03d", i), "r");
      stub.mutate =
          (request, response) -> {
            if (request.path().endsWith("/search")) {
              boolean last = request.body().path("filter").asString().contains("d128");
              response.set(
                  "data",
                  MilvusStub.JSON.valueToTree(
                      List.of(
                          hit(
                              last ? "last" : "first",
                              last ? "d128" : "d000",
                              "r",
                              last ? 0.9 : 0.5))));
            }
          };
      String question = "如何使用？".repeat(2000);
      var result =
          projection.search(
              new Query(question, List.of(1.0, 0.0), new AuthorizedScope("org-main", scope), 2));
      assertEquals(
          List.of("last", "first"),
          result.stream().map(RetrievalProjection.Candidate::segmentId).toList());
      var calls =
          stub.requests.stream().filter(request -> request.path().endsWith("/search")).toList();
      assertEquals(4, calls.size());
      assertEquals(2.0 / 61, result.getFirst().score(), 1e-12);
      assertTrue(
          calls.stream()
              .filter(request -> request.body().path("annsField").asString().equals("sparse"))
              .allMatch(request -> request.body().path("data").get(0).asString().equals(question)));
      for (String id : scope.keySet())
        assertEquals(
            2,
            calls.stream()
                .filter(
                    request -> request.body().path("filter").asString().contains("\"" + id + "\""))
                .count());
    }
  }

  @Test
  void identityIsStableWithoutNetworkAndBindsTargetButNotCredentialsOrBudgets() {
    var original = settings(URI.create("http://127.0.0.1:1"));
    try (var projection = new MilvusRestProjection(original)) {
      String identity = projection.identity();
      assertTrue(identity.matches("[a-f0-9]{64}"));
      assertEquals(identity, original.identity());
      assertEquals(
          identity,
          new MilvusRestProjection.Settings(
                  original.endpoint(),
                  "synthetic-credential",
                  "default",
                  "java_contract_test",
                  "org-main",
                  "fixture/embed@v1",
                  2,
                  Duration.ofSeconds(3),
                  2048,
                  true)
              .identity());
      for (var changed :
          List.of(
              new MilvusRestProjection.Settings(
                  URI.create("http://127.0.0.1:2"),
                  "",
                  "default",
                  "java_contract_test",
                  "org-main",
                  "fixture/embed@v1",
                  2,
                  original.timeout(),
                  1024,
                  true),
              new MilvusRestProjection.Settings(
                  original.endpoint(),
                  "",
                  "other",
                  "java_contract_test",
                  "org-main",
                  "fixture/embed@v1",
                  2,
                  original.timeout(),
                  1024,
                  true),
              new MilvusRestProjection.Settings(
                  original.endpoint(),
                  "",
                  "default",
                  "java_other",
                  "org-main",
                  "fixture/embed@v1",
                  2,
                  original.timeout(),
                  1024,
                  true),
              new MilvusRestProjection.Settings(
                  original.endpoint(),
                  "",
                  "default",
                  "java_contract_test",
                  "other",
                  "fixture/embed@v1",
                  2,
                  original.timeout(),
                  1024,
                  true),
              new MilvusRestProjection.Settings(
                  original.endpoint(),
                  "",
                  "default",
                  "java_contract_test",
                  "org-main",
                  "fixture/embed@v2",
                  2,
                  original.timeout(),
                  1024,
                  true),
              new MilvusRestProjection.Settings(
                  original.endpoint(),
                  "",
                  "default",
                  "java_contract_test",
                  "org-main",
                  "fixture/embed@v1",
                  3,
                  original.timeout(),
                  1024,
                  true))) {
        assertNotEquals(identity, changed.identity());
      }
    }
  }

  @Test
  void verifyReadsCompleteRevisionTwiceAndExactContentBatchesThenReturnsBoundReceipt()
      throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      assertThrows(
          ProjectionException.class,
          () -> projection.verify(manifest(List.of(entry("s", "d", "r")))));
      projection.initialize();
      List<Entry> entries = new ArrayList<>();
      for (int i = 0; i < 33; i++) {
        entries.add(new Entry("s" + i, "org-main", "d", "r", "合成\n🙂证据" + i, List.of(0.1, -0.0)));
      }
      projection.upsert(entries);
      int start = stub.requests.size();
      var manifest = manifest(entries);
      var receipt = projection.verify(manifest);
      assertEquals(projection.identity(), receipt.projectionIdentity());
      assertEquals(manifest.sha256(), receipt.manifestSha256());
      assertEquals(33, receipt.segmentCount());
      List<Request> calls = stub.requests.subList(start, stub.requests.size());
      List<Request> metadata =
          calls.stream()
              .filter(
                  r ->
                      r.path().endsWith("/query")
                          && !r.body().path("outputFields").toString().contains("dense"))
              .toList();
      assertEquals(2, metadata.size());
      for (Request request : metadata) {
        assertEquals("revision_id == \"r\"", request.body().path("filter").asString());
        assertEquals(34, request.body().path("limit").asInt());
        assertEquals(0, request.body().path("offset").asInt());
        assertFalse(request.body().has("consistencyLevel"));
      }
      List<Request> content =
          calls.stream()
              .filter(
                  r ->
                      r.path().endsWith("/query")
                          && r.body().path("outputFields").toString().contains("dense"))
              .toList();
      assertTrue(content.size() >= 3);
      int queried = 0;
      for (Request request : content) {
        JsonNode ids =
            MilvusStub.JSON.readTree(
                request.body().path("filter").asString().substring("id in ".length()));
        assertTrue(ids.size() <= 16);
        assertEquals(ids.size() + 1, request.body().path("limit").asInt());
        queried += ids.size();
      }
      assertEquals(33, queried);
      assertTrue(calls.getLast().path().endsWith("/collections/describe"));
      assertTrue(calls.stream().noneMatch(r -> r.path().endsWith("/search")));
    }
  }

  @Test
  void verifyRejectsMissingExtraDuplicateOrWrongScopeInFullMetadata() throws Exception {
    List<Consumer<ArrayNode>> changes =
        List.of(
            rows -> rows.remove(0),
            rows -> rows.add(rows.get(0).deepCopy()),
            rows -> {
              ObjectNode extra = ((ObjectNode) rows.get(0)).deepCopy();
              extra.put("id", "extra");
              rows.add(extra);
            },
            rows -> ((ObjectNode) rows.get(0)).put("workspace_id", "other"),
            rows -> ((ObjectNode) rows.get(0)).put("document_id", "other"),
            rows -> ((ObjectNode) rows.get(0)).put("revision_id", "other"),
            rows -> ((ObjectNode) rows.get(0)).put("id", "invalid\""),
            rows -> ((ObjectNode) rows.get(0)).remove("id"));
    for (Consumer<ArrayNode> change : changes) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        projection.initialize();
        List<Entry> entries = List.of(entry("s", "d", "r"));
        projection.upsert(entries);
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/query")) {
                change.accept((ArrayNode) response.path("data"));
              }
            };
        assertThrows(ProjectionException.class, () -> projection.verify(manifest(entries)));
        assertEquals(1, stub.requests.stream().filter(r -> r.path().endsWith("/query")).count());
      }
    }
  }

  @Test
  void verifyRejectsChangedTextOrExactFloatBitsAndMalformedContent() throws Exception {
    List<Consumer<ObjectNode>> changes =
        List.of(
            row -> row.put("text", "changed"),
            row -> row.put("text", "\uD800"),
            row -> row.put("text", " "),
            row -> row.remove("text"),
            row -> row.put("workspace_id", "other"),
            row -> row.put("document_id", "other"),
            row -> row.put("revision_id", "other"),
            row -> row.put("id", "other"),
            row ->
                row.set(
                    "dense", MilvusStub.JSON.valueToTree(List.of((double) Math.nextUp(1.0f), 0.0))),
            row -> row.set("dense", MilvusStub.JSON.valueToTree(List.of(0.0, 0.0))),
            row -> row.set("dense", MilvusStub.JSON.valueToTree(List.of(1.0))),
            row -> row.set("dense", MilvusStub.JSON.valueToTree(List.of(1.0, 0.0, 0.0))),
            row -> row.set("dense", MilvusStub.JSON.valueToTree(List.of("1.0", 0.0))),
            row -> row.set("dense", MilvusStub.JSON.valueToTree(List.of(Double.MAX_VALUE, 0.0))),
            row -> row.remove("dense"));
    for (Consumer<ObjectNode> change : changes) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        projection.initialize();
        List<Entry> entries = List.of(entry("s", "d", "r"));
        projection.upsert(entries);
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/query")
                  && request.body().path("outputFields").toString().contains("dense")) {
                change.accept((ObjectNode) response.path("data").get(0));
              }
            };
        var failure =
            assertThrows(ProjectionException.class, () -> projection.verify(manifest(entries)));
        assertFalse(failure.toString().contains("changed"));
        assertNull(failure.getCause());
      }
    }
  }

  @Test
  void verifyRejectsCollectionOrFullSetChangesAfterContentReadback() throws Exception {
    for (boolean schemaChange : List.of(false, true)) {
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        projection.initialize();
        List<Entry> entries = List.of(entry("s", "d", "r"));
        projection.upsert(entries);
        var contentSeen = new AtomicBoolean();
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/query")
                  && request.body().path("outputFields").toString().contains("dense")) {
                contentSeen.set(true);
              } else if (contentSeen.get()
                  && request.path().endsWith(schemaChange ? "/collections/describe" : "/query")) {
                if (schemaChange) {
                  ((ObjectNode) response.path("data")).put("consistencyLevel", "Session");
                } else {
                  ((ArrayNode) response.path("data")).remove(0);
                }
              }
            };
        assertThrows(ProjectionException.class, () -> projection.verify(manifest(entries)));
        assertTrue(contentSeen.get());
      }
    }
  }

  @Test
  void verifyRejectsIndexMetricTypeOrStateChangesAfterInitializationAndReadback() throws Exception {
    List<Map<String, String>> changes =
        List.of(
            Map.of("index", "dense_index", "field", "metricType", "value", "L2"),
            Map.of("index", "dense_index", "field", "indexType", "value", "HNSW"),
            Map.of("index", "dense_index", "field", "indexState", "value", "InProgress"),
            Map.of("index", "sparse_index", "field", "metricType", "value", "IP"),
            Map.of("index", "sparse_index", "field", "indexType", "value", "FLAT"),
            Map.of("index", "sparse_index", "field", "indexState", "value", "Failed"));
    for (boolean afterReadback : List.of(false, true)) {
      for (Map<String, String> change : changes) {
        try (var stub = new MilvusStub(true);
            var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
          projection.initialize();
          List<Entry> entries = List.of(entry("s", "d", "r"));
          projection.upsert(entries);
          var contentSeen = new AtomicBoolean();
          stub.mutate =
              (request, response) -> {
                if (request.path().endsWith("/query")
                    && request.body().path("outputFields").toString().contains("dense")) {
                  contentSeen.set(true);
                }
                if (request.path().endsWith("/indexes/describe")
                    && change.get("index").equals(request.body().path("indexName").asString())
                    && (!afterReadback || contentSeen.get())) {
                  ((ObjectNode) response.path("data").get(0))
                      .put(change.get("field"), change.get("value"));
                }
              };
          var failure =
              assertThrows(
                  ProjectionException.class,
                  () -> projection.verify(manifest(entries)),
                  change + " afterReadback=" + afterReadback);
          assertEquals("projection_schema_mismatch", failure.code());
          if (afterReadback) {
            assertTrue(contentSeen.get());
          }
        }
      }
    }
  }

  @Test
  void verifyDeadlineIsSharedAcrossBatchesAndCancellationInterruptsLockWait() throws Exception {
    try (var stub = new MilvusStub(true)) {
      var base = settings(stub.endpoint());
      var configured =
          new MilvusRestProjection.Settings(
              base.endpoint(),
              "",
              base.database(),
              base.collection(),
              base.workspaceId(),
              base.embeddingIdentity(),
              2,
              Duration.ofMillis(800),
              base.maxResponseBytes(),
              true);
      try (var projection = new MilvusRestProjection(configured)) {
        projection.initialize();
        List<Entry> entries = List.of(entry("s", "d", "r"));
        projection.upsert(entries);
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/query")) {
                sleep(350);
              }
            };
        long start = System.nanoTime();
        assertThrows(ProjectionException.class, () -> projection.verify(manifest(entries)));
        assertTrue(
            Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(1500)) < 0);
      }
    }
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      List<Entry> entries = List.of(entry("s", "d", "r"));
      projection.upsert(entries);
      stub.faultyPath = "/v2/vectordb/entities/query";
      stub.faultyBody = "{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
      stub.bodyDelayMillis = 1500;
      var firstFailure = new AtomicReference<ProjectionException>();
      Thread first =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      projection.verify(manifest(entries));
                    } catch (ProjectionException failure) {
                      firstFailure.set(failure);
                    }
                  });
      assertTrue(stub.headersSent.await(1, TimeUnit.SECONDS));
      var waitingFailure = new AtomicReference<ProjectionException>();
      var waitingInterrupted = new AtomicBoolean();
      Thread waiting =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      projection.verify(manifest(entries));
                    } catch (ProjectionException failure) {
                      waitingFailure.set(failure);
                      waitingInterrupted.set(Thread.currentThread().isInterrupted());
                    }
                  });
      waiting.interrupt();
      waiting.join(500);
      assertFalse(waiting.isAlive());
      assertEquals("projection_interrupted", waitingFailure.get().code());
      assertTrue(waitingInterrupted.get());
      first.interrupt();
      first.join(500);
      assertFalse(first.isAlive());
      assertEquals("projection_interrupted", firstFailure.get().code());
    }
  }

  private static void sleep(long milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void verifyRejectsDuplicateOrIncompleteExactIdContentEvenWhenMetadataWasComplete()
      throws Exception {
    for (int variant = 0; variant < 5; variant++) {
      int mutation = variant;
      try (var stub = new MilvusStub(true);
          var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
        projection.initialize();
        List<Entry> entries = List.of(entry("s1", "d", "r"), entry("s2", "d", "r"));
        projection.upsert(entries);
        stub.mutate =
            (request, response) -> {
              if (request.path().endsWith("/query")) {
                var rows = (ArrayNode) response.path("data");
                if (mutation == 0
                    && !request.body().path("outputFields").toString().contains("dense")) {
                  rows.set(1, rows.get(0).deepCopy());
                } else if (request.body().path("outputFields").toString().contains("dense")) {
                  if (mutation == 1) {
                    rows.remove(0);
                  } else if (mutation == 2) {
                    rows.add(rows.get(0).deepCopy());
                  } else if (mutation == 3) {
                    rows.set(1, rows.get(0).deepCopy());
                  } else if (mutation == 4) {
                    response.set("data", MilvusStub.JSON.createObjectNode());
                  }
                }
              }
            };
        assertThrows(ProjectionException.class, () -> projection.verify(manifest(entries)));
      }
    }
  }

  @Test
  void verifyShrinksDenseReadbackBatchesToConfiguredResponseBudget() throws Exception {
    try (var stub = new MilvusStub(true)) {
      var base = settings(stub.endpoint());
      int dimension = 8192;
      var configured =
          new MilvusRestProjection.Settings(
              base.endpoint(),
              "",
              base.database(),
              base.collection(),
              base.workspaceId(),
              base.embeddingIdentity(),
              dimension,
              Duration.ofSeconds(5),
              base.maxResponseBytes(),
              true);
      stub.mutate =
          (request, response) -> {
            if (request.path().endsWith("/collections/describe")) {
              ((ObjectNode) response.path("data"))
                  .put(
                      "description",
                      "evidence-rag-java-text-v1;workspace=org-main;embedding=fixture/embed@v1;dim="
                          + dimension);
              ((ObjectNode) response.path("data").path("fields").get(5).path("params").get(0))
                  .put("value", Integer.toString(dimension));
            }
          };
      try (var projection = new MilvusRestProjection(configured)) {
        projection.initialize();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
          entries.add(
              new Entry(
                  "s" + i, "org-main", "d", "r", "synthetic", Collections.nCopies(dimension, 1.0)));
        }
        projection.upsert(entries);
        assertEquals(5, projection.verify(manifest(entries)).segmentCount());
        var requests =
            stub.requests.stream()
                .filter(
                    r ->
                        r.path().endsWith("/query")
                            && r.body().path("outputFields").toString().contains("dense"))
                .toList();
        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(r -> r.body().path("limit").asInt() <= 3));
      }
    }
  }

  @Test
  void verifyRejectsInsufficientResponseBudgetOrWrongWorkspaceWithoutPublishing() throws Exception {
    try (var stub = new MilvusStub(true)) {
      var base = settings(stub.endpoint());
      var configured =
          new MilvusRestProjection.Settings(
              base.endpoint(),
              "",
              base.database(),
              base.collection(),
              base.workspaceId(),
              base.embeddingIdentity(),
              2,
              base.timeout(),
              32_768,
              true);
      try (var projection = new MilvusRestProjection(configured)) {
        projection.initialize();
        List<Entry> entries = List.of(entry("s", "d", "r"));
        projection.upsert(entries);
        var manifest = manifest(entries);
        int before = stub.requests.size();
        assertThrows(ProjectionException.class, () -> projection.verify(null));
        assertThrows(
            ProjectionException.class,
            () ->
                projection.verify(
                    new RevisionManifest("other", "d", "r", manifest.entryDigests())));
        assertEquals(before, stub.requests.size());
        assertEquals(
            "projection_response_budget_insufficient",
            assertThrows(ProjectionException.class, () -> projection.verify(manifest)).code());
        assertTrue(
            stub.requests.stream()
                .noneMatch(
                    r ->
                        r.path().endsWith("/query")
                            && r.body().path("outputFields").toString().contains("dense")));
      }
    }
  }

  @Test
  void largestManifestUsesAnExact4097MetadataLimitAndNeverAnnSampling() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      var digests = new TreeMap<String, String>();
      for (int index = 0; index < 4096; index++) {
        digests.put("s" + index, "a".repeat(64));
      }
      var manifest = new RevisionManifest("org-main", "d", "r", digests);
      assertThrows(ProjectionException.class, () -> projection.verify(manifest));
      var query =
          stub.requests.stream()
              .filter(r -> r.path().endsWith("/entities/query"))
              .findFirst()
              .orElseThrow();
      assertEquals(4097, query.body().path("limit").asInt());
      assertEquals(0, query.body().path("offset").asInt());
      assertTrue(stub.requests.stream().noneMatch(r -> r.path().endsWith("/search")));
    }
  }

  @Test
  void exact16KiBUtf8TextBoundarySurvivesUpsertAndFullReadback() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      List<String> texts = List.of("a".repeat(16_384), "汉".repeat(5461) + "a", "🙂".repeat(4096));
      List<Entry> entries = new ArrayList<>();
      for (int index = 0; index < texts.size(); index++) {
        String text = texts.get(index);
        assertEquals(16_384, text.getBytes(StandardCharsets.UTF_8).length);
        entries.add(new Entry("s" + index, "org-main", "d", "r", text, List.of(1.0, 0.0)));
        assertThrows(
            ProjectionException.class,
            () -> new Entry("oversized", "org-main", "d", "r", text + "a", List.of(1.0, 0.0)));
      }
      projection.upsert(entries);
      var manifest = manifest(entries);
      var receipt = projection.verify(manifest);
      assertEquals(3, receipt.segmentCount());
      assertEquals(manifest.sha256(), receipt.manifestSha256());
    }
  }

  private static RevisionManifest manifest(List<Entry> entries) {
    var first = entries.getFirst();
    var digests = new TreeMap<String, String>();
    entries.forEach(
        entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    return new RevisionManifest(
        first.workspaceId(), first.documentId(), first.revisionId(), digests);
  }

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
            ProjectionException.class,
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

  @Test
  void imageVectorDenseOnlySearchNeverSendsTextOrBm25AndKeepsScopeBeforeLimit() throws Exception {
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.prepareSearch();
      stub.dense =
          List.of(
              hit("vector-b", "doc-b", "image-generation-b", -0.5),
              hit("vector-a", "doc-a", "image-generation-a", 0.9));
      stub.sparse = List.of(hit("caption-only", "doc-a", "image-generation-a", 100));
      var scope =
          new AuthorizedScope(
              "org-main", Map.of("doc-a", "image-generation-a", "doc-b", "image-generation-b"));
      var found =
          projection.search(
              new Query(
                  "not sent as text",
                  List.of(1.0, 0.0),
                  scope,
                  2,
                  RetrievalProjection.SearchMode.DENSE_ONLY));
      assertEquals(
          List.of("vector-a", "vector-b"),
          found.stream().map(RetrievalProjection.Candidate::segmentId).toList());
      assertEquals(1.0 / 61, found.getFirst().score());
      assertEquals(1.0 / 62, found.getLast().score());
      var searches = stub.requests.stream().filter(r -> r.path().endsWith("/search")).toList();
      assertEquals(1, searches.size());
      var request = searches.getFirst().body();
      assertEquals("dense", request.path("annsField").asString());
      assertEquals("COSINE", request.path("searchParams").path("metricType").asString());
      assertEquals(2, request.path("limit").asInt());
      assertEquals(
          "workspace_id == \"org-main\" && ((document_id == \"doc-a\" && revision_id == \"image-generation-a\") || (document_id == \"doc-b\" && revision_id == \"image-generation-b\"))",
          request.path("filter").asString());
      assertEquals(MilvusStub.JSON.valueToTree(List.of(List.of(1.0, 0.0))), request.path("data"));
      assertFalse(request.toString().contains("not sent as text"));
      assertEquals(
          RetrievalProjection.SearchMode.HYBRID,
          new Query("legacy", List.of(1.0, 0.0), scope, 2).mode());
      assertThrows(
          ProjectionException.class, () -> new Query("legacy", List.of(1.0, 0.0), scope, 2, null));
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
            schema -> schema.remove("consistencyLevel"),
            schema -> schema.put("consistencyLevel", "Session"),
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
        var failure = assertThrows(ProjectionException.class, projection::initialize);
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
        assertThrows(ProjectionException.class, projection::initialize);
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
          ProjectionException.class, () -> projection.upsert(List.of(entry("s", "d", "r"))));
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
        assertThrows(ProjectionException.class, () -> projection.upsert(batch));
      }
      assertThrows(ProjectionException.class, () -> projection.search(null));
      assertThrows(
          ProjectionException.class,
          () ->
              projection.search(
                  new Query(
                      "q", List.of(1.0, 0.0), new AuthorizedScope("org-other", Map.of()), 1)));
      assertThrows(
          ProjectionException.class,
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
        assertThrows(ProjectionException.class, () -> projection.search(query()));
      }
    }
    try (var stub = new MilvusStub(true);
        var projection = new MilvusRestProjection(settings(stub.endpoint()))) {
      projection.initialize();
      stub.dense = Collections.nCopies(11, hit("s", "d", "r", 0.9));
      assertThrows(ProjectionException.class, () -> projection.search(query()));
      stub.dense = List.of(hit("s", "d", "r", 0.9));
      stub.sparse = List.of(hit("s", "other", "active", 0.9));
      assertThrows(
          ProjectionException.class,
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
            ProjectionException.class,
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
        var failure = assertThrows(ProjectionException.class, projection::initialize);
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
        var failure = assertThrows(ProjectionException.class, projection::initialize);
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
            assertThrows(ProjectionException.class, projection::initialize).code());
      }
      stub.faultyBody = "{\"code\":0,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8);
      stub.bodyDelayMillis = 1500;
      try (var projection = new MilvusRestProjection(configured)) {
        long started = System.nanoTime();
        assertThrows(ProjectionException.class, projection::initialize);
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
      var failure = new AtomicReference<ProjectionException>();
      var interrupted = new AtomicBoolean();
      Thread thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      projection.initialize();
                    } catch (ProjectionException result) {
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
      var failure = assertThrows(ProjectionException.class, projection::initialize);
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("127.0.0.1"));
      assertEquals(
          "projection_not_initialized",
          assertThrows(ProjectionException.class, () -> projection.search(query())).code());
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
            assertThrows(ProjectionException.class, projection::initialize).code());
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
                ProjectionException.class, () -> projection.upsert(List.of(entry("s", "d", "r"))));
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
            assertThrows(ProjectionException.class, projection::initialize).code());
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
    final Map<String, JsonNode> rows = new TreeMap<>();
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
          body.path("data")
              .forEach(
                  row -> {
                    ids.add(row.path("id").asString());
                    rows.put(row.path("id").asString(), row);
                  });
          data = Map.of("upsertCount", ids.size(), "upsertIds", ids);
        } else if (path.endsWith("/entities/query")) {
          String filter = body.path("filter").asString();
          var selected = new ArrayList<JsonNode>();
          for (JsonNode row : rows.values()) {
            boolean included =
                filter.startsWith("id in ")
                    ? JSON.readTree(filter.substring(6))
                        .toString()
                        .contains("\"" + row.path("id").asString() + "\"")
                    : filter.equals(
                        "revision_id == \"" + row.path("revision_id").asString() + "\"");
            if (included) {
              ObjectNode result = JSON.createObjectNode();
              body.path("outputFields")
                  .forEach(field -> result.set(field.asString(), row.path(field.asString())));
              selected.add(result);
            }
          }
          data = selected.stream().limit(body.path("limit").asInt()).toList();
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
}
