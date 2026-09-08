package com.evidence.rag.client.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ProjectionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MilvusSearchPreparationTest {
  @Test
  void existingProjectionCanBeSearchedWithoutCreatingLoadingOrWritingAnything() throws Exception {
    try (var remote = new ReadOnlyMilvus();
        var projection = remote.projection(2000)) {
      assertTrue(remote.paths.isEmpty(), "Client construction must not access the network");
      projection.prepareSearch();
      assertEquals(
          List.of(
              "collections/has", "collections/describe", "indexes/describe", "indexes/describe"),
          remote.paths);
      assertTrue(projection.search(query()).isEmpty());
      assertEquals(2, remote.paths.stream().filter("entities/search"::equals).count());
      assertReadOnly(remote);
    }
  }

  @Test
  void absentCollectionFailsClosedInsteadOfLazilyCreatingIt() throws Exception {
    try (var remote = new ReadOnlyMilvus();
        var projection = remote.projection(2000)) {
      remote.exists = false;
      var failure = assertThrows(ProjectionException.class, projection::prepareSearch);
      assertEquals("projection_not_initialized", failure.code());
      assertEquals(List.of("collections/has"), remote.paths);
      assertThrows(ProjectionException.class, () -> projection.search(query()));
      assertReadOnly(remote);
    }
  }

  @Test
  void everySchemaAndIndexMismatchRevokesPreviouslyPreparedQueryReadiness() throws Exception {
    for (String broken : List.of("has", "schema", "dense_index", "sparse_index")) {
      try (var remote = new ReadOnlyMilvus();
          var projection = remote.projection(2000)) {
        projection.prepareSearch();
        remote.broken = broken;
        assertThrows(ProjectionException.class, projection::prepareSearch, broken);
        int requests = remote.paths.size();
        assertEquals(
            "projection_not_initialized",
            assertThrows(ProjectionException.class, () -> projection.search(query())).code());
        assertEquals(requests, remote.paths.size(), "Invalid preparation must prevent search");
        assertReadOnly(remote);
      }
    }
  }

  @Test
  void preparationHasOneBudgetAcrossItsReadsAndDoesNotLeaveSearchReady() throws Exception {
    try (var remote = new ReadOnlyMilvus();
        var projection = remote.projection(250)) {
      remote.delayMillis = 90;
      long started = System.nanoTime();
      assertEquals(
          "projection_timeout",
          assertThrows(ProjectionException.class, projection::prepareSearch).code());
      assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
      assertEquals(
          "projection_not_initialized",
          assertThrows(ProjectionException.class, () -> projection.search(query())).code());
      assertReadOnly(remote);
    }
  }

  @Test
  void actualConnectionFailureIsNotRelabeledAsATimeout() throws Exception {
    try (var remote = new ReadOnlyMilvus();
        var projection = remote.projection(2000)) {
      remote.disconnectBeforeResponse = true;
      var failure = assertThrows(ProjectionException.class, projection::prepareSearch);
      assertEquals("projection_transport_failed", failure.code());
      assertNull(failure.getCause());
      assertEquals(
          "projection_not_initialized",
          assertThrows(ProjectionException.class, () -> projection.search(query())).code());
      assertReadOnly(remote);
    }
  }

  @Test
  void interruptionCancelsAnActualPendingReadAndKeepsTheInterruptFlag() throws Exception {
    try (var remote = new ReadOnlyMilvus();
        var projection = remote.projection(5000)) {
      remote.delayMillis = 4000;
      var code = new AtomicReference<String>();
      var interrupted = new AtomicBoolean();
      Thread caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      projection.prepareSearch();
                    } catch (ProjectionException failure) {
                      code.set(failure.code());
                      interrupted.set(Thread.currentThread().isInterrupted());
                    }
                  });
      try {
        assertTrue(remote.received.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertEquals("projection_interrupted", code.get());
        assertTrue(interrupted.get());
        assertThrows(ProjectionException.class, () -> projection.search(query()));
        assertReadOnly(remote);
      } finally {
        caller.interrupt();
        caller.join(2000);
      }
    }
  }

  @Test
  void preparationDoesNotPretendToLoadAnUnloadedCollection() throws Exception {
    try (var remote = new ReadOnlyMilvus();
        var projection = remote.projection(2000)) {
      remote.searchFailure = true;
      projection.prepareSearch();
      var failure = assertThrows(ProjectionException.class, () -> projection.search(query()));
      assertEquals("projection_invalid_response", failure.code());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("private upstream"));
      assertReadOnly(remote);
    }
  }

  private static RetrievalProjection.Query query() {
    return new RetrievalProjection.Query(
        "合成政策",
        List.of(1.0, 0.0),
        new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc-one", "generation-one")),
        4);
  }

  private static void assertReadOnly(ReadOnlyMilvus remote) {
    assertTrue(
        remote.paths.stream()
            .allMatch(
                path ->
                    List.of(
                            "collections/has",
                            "collections/describe",
                            "indexes/describe",
                            "entities/search")
                        .contains(path)),
        remote.paths.toString());
  }

  private static final class ReadOnlyMilvus implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final CountDownLatch received = new CountDownLatch(1);
    private volatile boolean exists = true;
    private volatile boolean searchFailure;
    private volatile boolean disconnectBeforeResponse;
    private volatile String broken = "";
    private volatile long delayMillis;

    ReadOnlyMilvus() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v2/vectordb/", this::serve);
      server.start();
    }

    MilvusRestProjection projection(int timeoutMillis) {
      return new MilvusRestProjection(
          new MilvusRestProjection.Settings(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "",
              "default",
              "java_readonly_test",
              "org-main",
              "fixture/embed@v1",
              2,
              Duration.ofMillis(timeoutMillis),
              16384,
              true));
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String path = exchange.getRequestURI().getPath().substring("/v2/vectordb/".length());
        paths.add(path);
        received.countDown();
        if (disconnectBeforeResponse) {
          return;
        }
        if (delayMillis > 0) {
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        Object data;
        if (path.equals("collections/has")) {
          data = Map.of("has", broken.equals("has") ? "true" : exists);
        } else if (path.equals("collections/describe")) {
          data = description();
        } else if (path.equals("indexes/describe")) {
          String name = request.path("indexName").asString();
          boolean dense = name.equals("dense_index");
          data =
              List.of(
                  Map.of(
                      "indexName",
                      name,
                      "fieldName",
                      dense ? "dense" : "sparse",
                      "indexType",
                      dense ? "FLAT" : "SPARSE_INVERTED_INDEX",
                      "metricType",
                      dense ? "COSINE" : "BM25",
                      "indexState",
                      broken.equals(name) ? "InProgress" : "Finished"));
        } else {
          data = List.of();
        }
        byte[] response =
            JSON.writeValueAsBytes(
                path.equals("entities/search") && searchFailure
                    ? Map.of(
                        "code",
                        101,
                        "message",
                        "private upstream collection not loaded",
                        "data",
                        Map.of())
                    : Map.of("code", 0, "data", data));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      }
    }

    private Map<String, Object> description() {
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
          "java_readonly_test",
          "description",
          "evidence-rag-java-text-v1;workspace=org-main;embedding=fixture/embed@v1;dim=2",
          "autoId",
          false,
          "enableDynamicField",
          false,
          "consistencyLevel",
          broken.equals("schema") ? "Eventually" : "Strong",
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
