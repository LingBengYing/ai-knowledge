package com.evidence.rag.client.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.worker.indexing.ProjectionCleanupExecutor;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MilvusProjectionCleanupTest {
  @Test
  void codeZeroEmptyDeleteDataRemovesAllRegisteredGenerationsAndPreservesOtherDocuments()
      throws Exception {
    try (var server = new Server()) {
      server.rows.add(row("failed", "removed", "failed-gen"));
      server.rows.add(row("active", "removed", "active-gen"));
      server.rows.add(row("retained", "other", "other-gen"));
      var settings = settings(server.endpoint());
      var result =
          ProjectionCleanupExecutor.clean(
              new MilvusProjectionCleanup(List.of(settings)),
              List.of(
                  attempt(settings, "removed", "failed-gen"),
                  attempt(settings, "removed", "active-gen")));
      assertEquals("completed", result.logicalRows());
      assertEquals("blocked", result.writeTerminal());
      assertEquals("blocked", result.physicalStorage());
      assertEquals("cleanup_remote_unverified", result.errorCode());
      assertEquals(List.of("retained"), server.rows.stream().map(r -> r.get("id")).toList());
      var deletion =
          server.calls.stream()
              .filter(call -> call.path().endsWith("/delete"))
              .findFirst()
              .orElseThrow()
              .body();
      assertEquals("default", deletion.path("dbName").textValue());
      assertEquals("java_cleanup_test", deletion.path("collectionName").textValue());
      assertEquals(
          "workspace_id == \"org-main\" && document_id == \"removed\"",
          deletion.path("filter").textValue());
      assertTrue(
          server.calls.stream()
              .noneMatch(
                  call -> call.path().matches(".*/(create|drop|compact|upsert|load|alter)$")));
      assertEquals(5, server.calls.stream().filter(call -> call.path().endsWith("/query")).count());
    }
  }

  @Test
  void foreignGenerationBindingIsRejectedBeforeAnyDeletion() throws Exception {
    try (var server = new Server()) {
      server.rows.add(row("foreign", "other", "collision-gen"));
      var settings = settings(server.endpoint());
      var result =
          new MilvusProjectionCleanup(List.of(settings))
              .clean(List.of(attempt(settings, "removed", "collision-gen")));
      assertEquals("failed", result.logicalRows());
      assertEquals("cleanup_projection_failed", result.errorCode());
      assertEquals(1, server.rows.size());
      assertTrue(server.calls.stream().noneMatch(call -> call.path().endsWith("/delete")));
    }
  }

  @Test
  void lateVisibleRowsAndMalformedAcknowledgementsNeverProveLogicalAbsence() throws Exception {
    for (boolean malformed : List.of(false, true)) {
      try (var server = new Server()) {
        server.rows.add(row("removed", "removed", "gen"));
        server.ignoreDelete = !malformed;
        server.malformedDelete = malformed;
        var settings = settings(server.endpoint());
        var result =
            new MilvusProjectionCleanup(List.of(settings))
                .clean(List.of(attempt(settings, "removed", "gen")));
        assertEquals("failed", result.logicalRows());
        assertEquals("blocked", result.writeTerminal());
        assertEquals("blocked", result.physicalStorage());
      }
    }
  }

  @Test
  void unknownPastTargetDoesNotFallBackToCurrentConfigurationOrCallRemote() throws Exception {
    try (var server = new Server()) {
      var settings = settings(server.endpoint());
      var unknown = settings(URI.create("http://127.0.0.1:1"));
      var result =
          new MilvusProjectionCleanup(List.of(settings))
              .clean(List.of(attempt(unknown, "removed", "gen")));
      assertEquals("blocked", result.logicalRows());
      assertEquals("cleanup_target_unknown", result.errorCode());
      assertTrue(server.calls.isEmpty());
    }
  }

  @Test
  void absentCollectionIsLogicalAbsenceWithoutCreateOrPhysicalClaim() throws Exception {
    try (var server = new Server()) {
      server.exists = false;
      var settings = settings(server.endpoint());
      var result =
          new MilvusProjectionCleanup(List.of(settings))
              .clean(List.of(attempt(settings, "removed", "gen")));
      assertEquals("completed", result.logicalRows());
      assertEquals("blocked", result.physicalStorage());
      assertEquals(1, server.calls.size());
      assertFalse(server.calls.stream().anyMatch(call -> call.path().endsWith("/create")));
    }
  }

  private static ProjectionAttempt attempt(
      MilvusRestProjection.Settings settings, String document, String generation) {
    return new ProjectionAttempt(
        document,
        "org-main",
        "source-revision",
        "a".repeat(64),
        generation,
        "legacy",
        MilvusProjectionCleanup.qualified(settings),
        true);
  }

  private static MilvusRestProjection.Settings settings(URI endpoint) {
    return new MilvusRestProjection.Settings(
        endpoint,
        "",
        "default",
        "java_cleanup_test",
        "org-main",
        "fixture/embed@v1",
        2,
        Duration.ofSeconds(2),
        1048576,
        true);
  }

  private static Map<String, String> row(String id, String document, String generation) {
    return Map.of(
        "id", id, "workspace_id", "org-main", "document_id", document, "revision_id", generation);
  }

  private record Call(String path, JsonNode body) {}

  private static final class Server implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Map<String, String>> rows = new CopyOnWriteArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private boolean exists = true;
    private boolean ignoreDelete;
    private boolean malformedDelete;

    private Server() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    private URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String path = exchange.getRequestURI().getPath();
        calls.add(new Call(path, body));
        Object data;
        if (path.endsWith("/collections/has")) {
          data = Map.of("has", exists);
        } else if (path.endsWith("/collections/describe")) {
          data = description();
        } else if (path.endsWith("/entities/query")) {
          String filter = body.path("filter").textValue();
          data =
              rows.stream()
                  .filter(
                      row ->
                          filter.equals("revision_id == \"" + row.get("revision_id") + "\"")
                              || filter.equals(
                                  "workspace_id == \""
                                      + row.get("workspace_id")
                                      + "\" && document_id == \""
                                      + row.get("document_id")
                                      + "\""))
                  .limit(body.path("limit").longValue())
                  .toList();
        } else if (path.endsWith("/entities/delete")) {
          if (!ignoreDelete) {
            String filter = body.path("filter").textValue();
            rows.removeIf(
                row ->
                    filter.equals(
                        "workspace_id == \""
                            + row.get("workspace_id")
                            + "\" && document_id == \""
                            + row.get("document_id")
                            + "\""));
          }
          data = malformedDelete ? List.of() : Map.of();
        } else {
          exchange.sendResponseHeaders(404, -1);
          return;
        }
        byte[] response = JSON.writeValueAsBytes(Map.of("code", 0, "data", data));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
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
          "java_cleanup_test",
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
