package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual local Spring, SQLite and parser/index children; all remote protocols are synthetic. */
class ManagedTextRoleSwitchMainlineHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TEXT = "合成差旅政策。上海住宿标准为每晚650元。";
  private static final String QUESTION = "上海住宿标准是多少？";
  private static final String INITIAL = "fixture-model";
  private static final String GENERATION = "fixture-generation-v2";
  private static final String RERANK = "fixture-rerank-v3";
  private static final String KEY = "synthetic-role-switch-key";
  @TempDir Path directory;

  @Test
  void generationAndRerankSwitchUseNewRolesWithoutReindexingAndSurviveRestart() throws Exception {
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      String documentId;
      String oldAnswerId;
      String oldSourceUrl;
      JsonNode oldSource;
      Map<String, String> protectedRows;
      int upserts;
      try (var context = start(models, projection)) {
        String base = base(context);
        Path database = context.getBean(SqliteAuthorityStore.class).libraryPath();
        var empty = request(http, base, "GET", "/v1/model-configuration", null, null, 200);
        assertEquals("unconfigured", empty.path("state").asString());
        assertEquals(0, empty.path("version").asInt());
        assertTrue(models.requests.isEmpty());
        assertTrue(projection.requests.isEmpty());
        save(http, base, models, projection, 0, INITIAL, INITIAL, "fixture-v1", true);
        for (String role : List.of("embedding", "rerank", "generation")) {
          testRole(http, base, models, projection, 1, role, INITIAL);
        }
        activate(http, base, models, projection, 1, 200);
        byte[] content = TEXT.getBytes(StandardCharsets.UTF_8);
        var uploaded =
            request(
                http,
                base,
                "POST",
                "/v1/documents?filename=role-switch-policy.txt",
                content,
                "application/octet-stream",
                202);
        documentId = uploaded.path("document_id").asString();
        await(http, base, "ingestions", uploaded.path("task_id").asString(), "parsed");
        var indexed =
            request(http, base, "POST", "/v1/documents/" + documentId + "/index", null, null, 202);
        await(http, base, "indexings", indexed.path("task_id").asString(), "indexed");
        assertFalse(projection.committedUpserts.isEmpty());
        upserts = projection.committedUpserts.size();
        var answer = exercise(http, base, models, database, documentId, 1, INITIAL, INITIAL);
        oldAnswerId = answer.path("answer_id").asString();
        oldSourceUrl = answer.path("citations").get(0).path("source_url").asString();
        oldSource = request(http, base, "GET", oldSourceUrl, null, null, 200);
        assertEquals(answer.path("citations").get(0), oldSource.path("citation"));
        protectedRows = protectedRows(database, oldAnswerId);

        save(http, base, models, projection, 1, INITIAL, GENERATION, "fixture-v1", false);
        testRole(http, base, models, projection, 2, "generation", GENERATION);
        // On the unchanged 0032 product this first role-only activation is the business RED.
        activate(http, base, models, projection, 2, 200);
        assertEquals(protectedRows, protectedRows(database, oldAnswerId));
        assertEquals(upserts, projection.committedUpserts.size());
        exercise(http, base, models, database, documentId, 2, INITIAL, GENERATION);
        assertEquals(oldSource, request(http, base, "GET", oldSourceUrl, null, null, 200));

        save(http, base, models, projection, 2, RERANK, GENERATION, "fixture-v1", false);
        testRole(http, base, models, projection, 3, "rerank", RERANK);
        activate(http, base, models, projection, 3, 200);
        exercise(http, base, models, database, documentId, 3, RERANK, GENERATION);
        assertEquals(protectedRows, protectedRows(database, oldAnswerId));
        assertEquals(upserts, projection.committedUpserts.size());
        assertEquals(oldSource, request(http, base, "GET", oldSourceUrl, null, null, 200));
        assertOriginal(http, base, documentId);
      }

      int requests = models.requests.size() + projection.requests.size();
      try (var restored = start(models, projection)) {
        String base = base(restored);
        Path database = restored.getBean(SqliteAuthorityStore.class).libraryPath();
        assertEquals(requests, models.requests.size() + projection.requests.size());
        var active = request(http, base, "GET", "/v1/model-configuration", null, null, 200);
        assertEquals(3, active.path("active_version").asInt());
        assertEquals(RERANK, active.path("rerank").path("model").asString());
        assertEquals(GENERATION, active.path("generation").path("model").asString());
        assertEquals(oldSource, request(http, base, "GET", oldSourceUrl, null, null, 200));
        assertOriginal(http, base, documentId);
        assertEquals(requests, models.requests.size() + projection.requests.size());
        exercise(http, base, models, database, documentId, 3, RERANK, GENERATION);

        save(http, base, models, projection, 3, RERANK, GENERATION, "fixture-v2", false);
        var rejected = activate(http, base, models, projection, 4, 409);
        assertEquals("model_rebuild_required", rejected.path("error_code").asString());
        assertEquals(
            3,
            request(http, base, "GET", "/v1/model-configuration", null, null, 200)
                .path("active_version")
                .asInt());
        assertEquals(oldSource, request(http, base, "GET", oldSourceUrl, null, null, 200));
        assertEquals(protectedRows, protectedRows(database, oldAnswerId));
        assertEquals(upserts, projection.committedUpserts.size());
        assertTrue(
            projection.requests.stream()
                .noneMatch(
                    call ->
                        call.path().endsWith("/entities/delete")
                            || call.path().endsWith("/collections/drop")
                            || call.path().endsWith("/collections/create")));
      }
    }
  }

  private static JsonNode exercise(
      HttpClient http,
      String base,
      AnswerProtocolServer models,
      Path database,
      String documentId,
      int version,
      String rerank,
      String generation)
      throws Exception {
    var question = Map.of("question", QUESTION, "document_ids", List.of(documentId));
    int before = models.requests.size();
    var retrieved =
        request(
            http,
            base,
            "POST",
            "/v1/retrieval-tests",
            JSON.writeValueAsBytes(question),
            "application/json",
            200);
    assertEquals("completed", retrieved.path("status").asString(), retrieved.toString());
    assertEquals(version, retrieved.path("configuration_version").asInt());
    assertEquals(1, retrieved.path("scope_count").asInt());
    assertEquals("rrf", retrieved.path("score_kind").asString());
    var match = retrieved.path("matches").get(0);
    assertEquals(documentId, match.path("document_id").asString());
    assertEquals(
        ModelValues.sha256(TEXT.getBytes(StandardCharsets.UTF_8)),
        match.path("source_sha256").asString());
    assertTrue(match.path("text").asString().contains("650"));
    var recallCalls = List.copyOf(models.requests.subList(before, models.requests.size()));
    assertEquals(
        List.of("/embeddings", "/rerank"),
        recallCalls.stream().map(AnswerProtocolServer.Request::path).toList());
    assertModels(recallCalls, rerank, generation);

    before = models.requests.size();
    var answer =
        request(
            http,
            base,
            "POST",
            "/v1/answers",
            JSON.writeValueAsBytes(question),
            "application/json",
            200);
    assertEquals("answered", answer.path("status").asString(), answer.toString());
    assertTrue(answer.path("answer").asString().contains("650"));
    var answerCalls = List.copyOf(models.requests.subList(before, models.requests.size()));
    assertEquals(1, answerCalls.stream().filter(call -> call.path().equals("/embeddings")).count());
    assertEquals(1, answerCalls.stream().filter(call -> call.path().equals("/rerank")).count());
    assertTrue(answerCalls.stream().anyMatch(call -> call.path().equals("/chat/completions")));
    assertModels(answerCalls, rerank, generation);
    assertEquals(
        JSON.writeValueAsString(
            List.of(List.of(modelsRevision(models.endpoint(), rerank, generation)))),
        rows(
            database,
            "SELECT model_revision FROM query_traces WHERE id=?",
            answer.path("answer_id").asString()));
    return answer;
  }

  private static void assertModels(
      List<AnswerProtocolServer.Request> calls, String rerank, String generation) {
    for (var call : calls) {
      String expected =
          switch (call.path()) {
            case "/embeddings" -> INITIAL;
            case "/rerank" -> rerank;
            case "/chat/completions" -> generation;
            default -> throw new AssertionError("Unexpected synthetic model route: " + call.path());
          };
      assertEquals(expected, call.body().path("model").asString());
    }
  }

  private static String modelsRevision(URI endpoint, String rerank, String generation) {
    try (var models =
        new OpenAiCompatibleModels(
            new OpenAiCompatibleModels.Configuration(
                new OpenAiCompatibleModels.Endpoint(endpoint, INITIAL, KEY),
                new OpenAiCompatibleModels.Endpoint(endpoint, rerank, KEY),
                new OpenAiCompatibleModels.Endpoint(endpoint, generation, KEY),
                2,
                Duration.ofSeconds(5),
                4 * 1024 * 1024,
                true))) {
      return models.revision();
    }
  }

  private static void save(
      HttpClient http,
      String base,
      AnswerProtocolServer models,
      IndexingTestServer projection,
      int version,
      String rerank,
      String generation,
      String embeddingRevision,
      boolean keys)
      throws Exception {
    int before = models.requests.size() + projection.requests.size();
    var saved =
        request(
            http,
            base,
            "PUT",
            "/v1/model-configuration",
            JSON.writeValueAsBytes(
                configuration(version, rerank, generation, embeddingRevision, keys)),
            "application/json",
            200);
    assertEquals(version + 1, saved.path("version").asInt());
    assertEquals("draft", saved.path("state").asString());
    assertFalse(saved.toString().contains(KEY));
    assertEquals(before, models.requests.size() + projection.requests.size());
  }

  private static void testRole(
      HttpClient http,
      String base,
      AnswerProtocolServer models,
      IndexingTestServer projection,
      int version,
      String role,
      String model)
      throws Exception {
    int before = models.requests.size();
    int projectionBefore = projection.requests.size();
    var tested =
        request(
            http,
            base,
            "POST",
            "/v1/model-configuration/test",
            JSON.writeValueAsBytes(Map.of("version", version, "role", role)),
            "application/json",
            200);
    assertEquals("passed", tested.path("status").asString(), tested.toString());
    assertEquals(version, tested.path("version").asInt());
    assertEquals(role, tested.path("role").asString());
    assertEquals(before + 1, models.requests.size());
    assertEquals(projectionBefore, projection.requests.size());
    var call = models.requests.get(before);
    assertEquals(
        switch (role) {
          case "embedding" -> "/embeddings";
          case "rerank" -> "/rerank";
          case "generation" -> "/chat/completions";
          default -> throw new AssertionError("Unexpected test role");
        },
        call.path());
    assertEquals(model, call.body().path("model").asString());
  }

  private static JsonNode activate(
      HttpClient http,
      String base,
      AnswerProtocolServer models,
      IndexingTestServer projection,
      int version,
      int status)
      throws Exception {
    int before = models.requests.size() + projection.requests.size();
    var result =
        request(
            http,
            base,
            "POST",
            "/v1/model-configuration/activate",
            JSON.writeValueAsBytes(Map.of("version", version)),
            "application/json",
            status);
    assertEquals(before, models.requests.size() + projection.requests.size());
    if (status == 200) {
      assertEquals(version, result.path("active_version").asInt());
      assertEquals("active", result.path("state").asString());
    }
    return result;
  }

  private static Map<String, String> protectedRows(Path database, String traceId) throws Exception {
    var snapshot = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "indexing_jobs",
            "indexing_attempts",
            "index_publications",
            "index_publication_entries",
            "active_corpus_publications")) {
      String contents = rows(database, "SELECT * FROM " + table + " ORDER BY 1,2");
      assertFalse(contents.equals("[]"), table);
      snapshot.put(table, contents);
    }
    snapshot.put("query_traces", rows(database, "SELECT * FROM query_traces WHERE id=?", traceId));
    for (String table : List.of("query_trace_documents", "query_trace_evidence")) {
      String contents =
          rows(database, "SELECT * FROM " + table + " WHERE trace_id=? ORDER BY 2", traceId);
      assertFalse(contents.equals("[]"), table);
      snapshot.put(table, contents);
    }
    return Map.copyOf(snapshot);
  }

  private static String rows(Path database, String sql, String... parameters) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setString(i + 1, parameters[i]);
      }
      try (var result = statement.executeQuery()) {
        var values = new ArrayList<List<String>>();
        while (result.next()) {
          var row = new ArrayList<String>();
          for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
            row.add(result.getString(i));
          }
          values.add(row);
        }
        return JSON.writeValueAsString(values);
      }
    }
  }

  private static void assertOriginal(HttpClient http, String base, String documentId)
      throws Exception {
    var metadata =
        request(http, base, "GET", "/v1/documents/" + documentId + "/original", null, null, 200);
    byte[] original = TEXT.getBytes(StandardCharsets.UTF_8);
    assertEquals(ModelValues.sha256(original), metadata.path("source_sha256").asString());
    var content =
        http.send(
            headers(base, metadata.path("content_url").asString()).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, content.statusCode());
    assertArrayEquals(original, content.body());
  }

  private ConfigurableApplicationContext start(
      AnswerProtocolServer models, IndexingTestServer projection) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var defaults = new LinkedHashMap<String, Object>();
    defaults.put("RAG_MILVUS_ENDPOINT", projection.endpoint().toASCIIString());
    defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    defaults.put("RAG_MILVUS_COLLECTION", "java_index_process_fixture");
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(defaults);
    return application.run(
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--rag.environment=test",
        "--rag.workspace-id=org-main",
        "--rag.auth-mode=development_headers",
        "--rag.data-directory=" + directory,
        "--rag.ingestion.enabled=true",
        "--rag.indexing.enabled=true",
        "--rag.answers.enabled=true",
        "--rag.model-configuration.enabled=true",
        "--rag.model-configuration.administrators=owner",
        "--rag.model-configuration.provider-base-url=" + models.endpoint(),
        "--rag.model-configuration.allow-loopback-http=true",
        "--rag.model-configuration.deadline-ms=5000",
        "--rag.ingestion.parse-timeout-ms=15000",
        "--rag.indexing.timeout-ms=15000",
        "--rag.answers.timeout-ms=15000");
  }

  private static String base(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  private static Map<String, Object> configuration(
      int version, String rerank, String generation, String embeddingRevision, boolean keys) {
    var embedded = new LinkedHashMap<String, Object>();
    embedded.put("model", INITIAL);
    embedded.put("dimensions", 2);
    embedded.put("revision", embeddingRevision);
    var ranked = new LinkedHashMap<String, Object>();
    ranked.put("model", rerank);
    var generated = new LinkedHashMap<String, Object>();
    generated.put("model", generation);
    if (keys) {
      embedded.put("api_key", KEY);
      ranked.put("api_key", KEY);
      generated.put("api_key", KEY);
    }
    return Map.of(
        "base_version", version, "embedding", embedded, "rerank", ranked, "generation", generated);
  }

  private static HttpRequest.Builder headers(String base, String path) {
    return HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(20))
        .header("X-Workspace-Id", "org-main")
        .header("X-Principal-Id", "owner")
        .header("Origin", base);
  }

  private static JsonNode request(
      HttpClient http,
      String base,
      String method,
      String path,
      byte[] content,
      String contentType,
      int status)
      throws Exception {
    var builder = headers(base, path);
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    var response =
        http.send(
            builder
                .method(
                    method,
                    content == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static void await(HttpClient http, String base, String resource, String id, String state)
      throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      var result = request(http, base, "GET", "/v1/" + resource + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(result.path("state").asString())) {
        assertEquals(state, result.path("state").asString(), result.toString());
        return;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < until);
    fail("Synthetic role-switch " + resource + " did not finish");
  }
}
