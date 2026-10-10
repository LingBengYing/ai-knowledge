package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reproduces the new Wiki model page's explicit embedding revision migration. */
class WikiModelRebuildHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"", "changed-text"})
  void chineseAnalyzerMigrationCopiesVerifiedVectorsWithoutCallingModelsOrReparsing(
      String corruption) throws Exception {
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint(), true);
        var http = HttpClient.newHttpClient();
        var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      WikiLocalIntegrationServer.activate(http, base);
      var upload =
          request(
              http,
              base,
              "POST",
              "/v1/documents?filename=chinese-source.txt",
              "合成中文资料：外国企业或者个人可以依法设立合伙企业。".getBytes(StandardCharsets.UTF_8),
              "application/octet-stream",
              202);
      awaitTask(http, base, "/v1/ingestions/" + upload.path("task_id").asString(), "parsed");
      String document = upload.path("document_id").asString();
      var index =
          request(http, base, "POST", "/v1/documents/" + document + "/index", null, null, 202);
      awaitTask(http, base, "/v1/indexings/" + index.path("task_id").asString(), "indexed");
      var store = app.getBean(SqliteAuthorityStore.class);
      var publications = new IndexingRepository(store);
      var before = store.transaction(() -> publications.activePublication(document).orElseThrow());
      int modelRequests = models.requests.size();
      int projectionRequests = projection.requests.size();
      projection.failureMode = corruption;
      app.getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "chinese-test-profile", Map.of("RAG_MILVUS_ANALYZER", "chinese")));
      save(http, base, 1, "fixture-v1", "fixture-model");
      var eligibility =
          request(http, base, "GET", "/v1/model-configuration/rebuild", null, null, 200);
      assertTrue(eligibility.path("required").asBoolean(), eligibility.toString());
      request(
          http,
          base,
          "POST",
          "/v1/model-configuration/rebuild",
          JSON.writeValueAsBytes(Map.of("version", 2)),
          "application/json",
          202);
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      JsonNode state;
      do {
        state = request(http, base, "GET", "/v1/model-configuration/rebuild", null, null, 200);
        if (!List.of("queued", "running", "applying")
            .contains(state.path("job").path("state").asString())) break;
        Thread.sleep(40);
      } while (System.nanoTime() < deadline);
      assertEquals(
          corruption.isEmpty() ? "completed" : "failed",
          state.path("job").path("state").asString(),
          state.toString());
      assertEquals(
          modelRequests, models.requests.size(), "Analyzer migration must not embed or generate");
      var after = store.transaction(() -> publications.activePublication(document).orElseThrow());
      assertEquals(before.sourceSha256(), after.sourceSha256());
      assertEquals(before.revisionId(), after.revisionId());
      assertEquals(before.target().embeddingIdentity(), after.target().embeddingIdentity());
      if (!corruption.isEmpty()) {
        assertEquals(before, after, "Receipt mismatch must keep the original active publication");
        return;
      }
      assertNotEquals(before.id(), after.id());
      assertNotEquals(before.target().projectionIdentity(), after.target().projectionIdentity());
      assertTrue(
          projection.requests.subList(projectionRequests, projection.requests.size()).stream()
              .anyMatch(
                  r ->
                      r.path().endsWith("/collections/create")
                          && "chinese"
                              .equals(
                                  r.body()
                                      .path("schema")
                                      .path("fields")
                                      .get(4)
                                      .path("elementTypeParams")
                                      .path("analyzer_params")
                                      .path("type")
                                      .asString())));
    }
  }

  @Test
  void savedEmbeddingRevisionRebuildsAndAppliesUsingTheWikiRuntimeAdapters() throws Exception {
    assertNotNull(directory);
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint(), true);
        var http = HttpClient.newHttpClient();
        var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
      assertEquals(directory.toString(), app.getEnvironment().getProperty("rag.data-directory"));
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      WikiLocalIntegrationServer.activate(http, base);
      var upload =
          request(
              http,
              base,
              "POST",
              "/v1/documents?filename=rebuild-wiki.txt",
              "Synthetic Wiki project. The budget is CNY 48600.".getBytes(StandardCharsets.UTF_8),
              "application/octet-stream",
              202);
      awaitTask(http, base, "/v1/ingestions/" + upload.path("task_id").asString(), "parsed");
      String documentId = upload.path("document_id").asString();
      var index =
          request(http, base, "POST", "/v1/documents/" + documentId + "/index", null, null, 202);
      awaitTask(http, base, "/v1/indexings/" + index.path("task_id").asString(), "indexed");
      save(http, base, 1, "fixture-v1", "fixture-model-wiki");
      request(
          http,
          base,
          "POST",
          "/v1/model-configuration/activate",
          JSON.writeValueAsBytes(Map.of("version", 2)),
          "application/json",
          200);
      save(http, base, 2, "fixture-v2", "fixture-model-wiki");
      var eligibility =
          request(http, base, "GET", "/v1/model-configuration/rebuild", null, null, 200);
      assertTrue(eligibility.path("required").asBoolean(), eligibility.toString());
      assertTrue(eligibility.path("can_start").asBoolean(), eligibility.toString());
      int priorProjectionRequests = projection.requests.size();
      request(
          http,
          base,
          "POST",
          "/v1/model-configuration/rebuild",
          JSON.writeValueAsBytes(Map.of("version", 3)),
          "application/json",
          202);
      long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      JsonNode state;
      do {
        state = request(http, base, "GET", "/v1/model-configuration/rebuild", null, null, 200);
        if (!List.of("queued", "running", "applying")
            .contains(state.path("job").path("state").asString())) break;
        Thread.sleep(40);
      } while (System.nanoTime() < until);
      String projectionOperations =
          projection.requests.subList(priorProjectionRequests, projection.requests.size()).stream()
              .map(
                  value ->
                      value.path()
                          + " collection="
                          + value.body().path("collectionName").asString())
              .toList()
              .toString();
      assertEquals(
          "completed",
          state.path("job").path("state").asString(),
          state + " projection_operations=" + projectionOperations);
      assertEquals(1, state.path("job").path("completed_documents").asInt());
      var configuration = request(http, base, "GET", "/v1/model-configuration", null, null, 200);
      assertEquals(3, configuration.path("active_version").asInt());
    }
  }

  private static void save(
      HttpClient http, String base, int version, String revision, String generation)
      throws Exception {
    request(
        http,
        base,
        "PUT",
        "/v1/model-configuration",
        JSON.writeValueAsBytes(
            Map.of(
                "base_version", version,
                "embedding",
                    Map.of("model", "fixture-model", "dimensions", 2, "revision", revision),
                "rerank", Map.of("model", "fixture-model"),
                "generation", Map.of("model", generation))),
        "application/json",
        200);
  }

  private static void awaitTask(HttpClient http, String base, String path, String expected)
      throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    do {
      var state = request(http, base, "GET", path, null, null, 200);
      if (!List.of("queued", "processing").contains(state.path("state").asString())) {
        assertEquals(expected, state.path("state").asString(), state.toString());
        return;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < until);
    fail("Local task did not finish");
  }

  @Test
  void invalidRebuildRequestIsAClientProblemNotAServerFault() throws Exception {
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint(), true);
        var http = HttpClient.newHttpClient();
        var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      WikiLocalIntegrationServer.activate(http, base);
      for (byte[] body :
          List.of(
              "{}".getBytes(StandardCharsets.UTF_8),
              "{\"version\":\"x\"}".getBytes(StandardCharsets.UTF_8))) {
        var problem =
            request(
                http,
                base,
                "POST",
                "/v1/model-configuration/rebuild",
                body,
                "application/json",
                422);
        assertEquals("invalid_model_configuration", problem.path("error_code").asString());
      }
    }
  }

  private static JsonNode request(
      HttpClient http,
      String base,
      String method,
      String path,
      byte[] body,
      String type,
      int expected)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Origin", base)
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "owner");
    if (type != null) builder.header("Content-Type", type);
    var response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
