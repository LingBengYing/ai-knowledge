package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionTaskProcessor;
import com.evidence.rag.service.ManagedTextRuntime;
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

/**
 * Real local HTTP/SQLite/parser/index children. Every model and projection is synthetic loopback.
 */
class ManagedTextReindexMainlineHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TEXT = "合成差旅政策。上海住宿标准为每晚650元。";
  private static final String QUESTION = "上海住宿标准是多少？";
  private static final String INITIAL = "fixture-model";
  private static final String GENERATION = "fixture-generation-v2";
  private static final String KEY = "synthetic-role-switch-key";
  @TempDir Path directory;

  @Test
  void savedSourceRebuildPublishesFreshGenerationWithoutChangingTheOldHistory() throws Exception {
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      String document;
      String oldSourceUrl;
      String nextPublication;
      Map<String, String> history;
      Seed seed;
      try (var context = start(models, projection)) {
        String base = base(context);
        Path database = context.getBean(SqliteAuthorityStore.class).libraryPath();
        seed = seed(context, http, models, projection);
        document = seed.document();
        var answered = exercise(http, base, models, database, document, 1, INITIAL, INITIAL);
        oldSourceUrl = answered.path("citations").get(0).path("source_url").asString();
        request(http, base, "GET", oldSourceUrl, null, null, 200);
        history = history(database, seed, answered.path("answer_id").asString());

        save(http, base, models, projection, 1, INITIAL, GENERATION, "fixture-v1", false);
        testRole(http, base, models, projection, 2, "generation", GENERATION);
        activate(http, base, models, projection, 2, 200);
        int before = calls(models, projection);
        // First product RED on frozen0034: this exact route returns404, not the required202.
        var queued = reindex(http, base, document, seed.publication(), 202);
        String task = queued.path("task_id").asString();
        assertNotEquals(seed.task(), task);
        assertEquals("queued", queued.path("state").asString());
        assertEquals(seed.revision(), queued.path("revision_id").asString());
        assertEquals(before, calls(models, projection));
        assertEquals(seed.publication(), active(database, document));
        assertReindexEntry(
            http, base, document, seed.revision(), seed.publication(), task, "queued", false);
        request(http, base, "GET", oldSourceUrl, null, null, 200);
        reindex(http, base, document, seed.publication(), 409);
        assertEquals(before, calls(models, projection));

        var claim = claim(context, task);
        assertEquals(seed.revision(), claim.revisionId());
        assertEquals(seed.claim().target(), claim.target());
        assertNotEquals(seed.claim().projectionGenerationId(), claim.projectionGenerationId());
        assertEquals(seed.claim().items(), claim.items());
        assertEquals(seed.publication(), active(database, document));
        request(http, base, "GET", oldSourceUrl, null, null, 200);
        int embeddings = count(models, "/embeddings");
        int generations = count(models, "/chat/completions");
        int reranks = count(models, "/rerank");
        process(context, claim);
        var done = status(http, base, task, "indexed");
        nextPublication = done.path("index_publication_id").asString();
        assertNotEquals(seed.publication(), nextPublication);
        assertEquals(nextPublication, active(database, document));
        assertReindexEntry(
            http, base, document, seed.revision(), nextPublication, task, "indexed", true);
        assertEquals(embeddings + 1, count(models, "/embeddings"));
        assertEquals(generations, count(models, "/chat/completions"));
        assertEquals(reranks, count(models, "/rerank"));
        assertEquals(history, history(database, seed, answered.path("answer_id").asString()));
        request(http, base, "GET", oldSourceUrl, null, null, 404);
        assertOriginal(http, base, document);

        before = calls(models, projection);
        reindex(http, base, document, seed.publication(), 409);
        assertEquals(before, calls(models, projection));
        var currentAnswer =
            exercise(http, base, models, database, document, 2, INITIAL, GENERATION);
        request(
            http,
            base,
            "GET",
            currentAnswer.path("citations").get(0).path("source_url").asString(),
            null,
            null,
            200);
        var lastSearch =
            projection.requests.stream()
                .filter(r -> r.path().endsWith("/entities/search"))
                .reduce((a, b) -> b)
                .orElseThrow();
        String requestBody = lastSearch.body().toString();
        assertTrue(requestBody.contains(claim.projectionGenerationId()));
        assertFalse(requestBody.contains(seed.claim().projectionGenerationId()));
      }
      int before = calls(models, projection);
      try (var restored = start(models, projection)) {
        String base = base(restored);
        Path database = restored.getBean(SqliteAuthorityStore.class).libraryPath();
        assertEquals(before, calls(models, projection));
        assertEquals(nextPublication, active(database, document));
        status(http, base, seed.task(), "indexed");
        request(http, base, "GET", oldSourceUrl, null, null, 404);
        assertOriginal(http, base, document);
        assertEquals(before, calls(models, projection));
        exercise(http, base, models, database, document, 2, INITIAL, GENERATION);
      }
    }
  }

  @Test
  void cancelFailureRetryAndRestartCannotDisplaceTheOriginalActivePublication() throws Exception {
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      Seed seed;
      String task;
      String source;
      IndexClaim interrupted;
      try (var context = start(models, projection)) {
        String base = base(context);
        Path database = context.getBean(SqliteAuthorityStore.class).libraryPath();
        seed = seed(context, http, models, projection);
        var answered = exercise(http, base, models, database, seed.document(), 1, INITIAL, INITIAL);
        source = answered.path("citations").get(0).path("source_url").asString();
        int before = calls(models, projection);
        task =
            reindex(http, base, seed.document(), seed.publication(), 202)
                .path("task_id")
                .asString();
        var cancelled =
            request(http, base, "POST", "/v1/indexings/" + task + "/cancel", null, null, 200);
        assertEquals("cancelled", cancelled.path("state").asString());
        assertReindexEntry(
            http,
            base,
            seed.document(),
            seed.revision(),
            seed.publication(),
            task,
            "cancelled",
            true);
        assertEquals(before, calls(models, projection));
        assertEquals(seed.publication(), active(database, seed.document()));
        request(http, base, "GET", source, null, null, 200);

        var retry =
            request(http, base, "POST", "/v1/indexings/" + task + "/retry", null, null, 200);
        assertEquals(2, retry.path("attempt").asInt());
        var failedClaim = claim(context, task);
        projection.failureMode = "missing-verification";
        process(context, failedClaim);
        status(http, base, task, "failed");
        projection.failureMode = "";
        assertEquals(seed.publication(), active(database, seed.document()));
        request(http, base, "GET", source, null, null, 200);

        retry = request(http, base, "POST", "/v1/indexings/" + task + "/retry", null, null, 200);
        assertEquals(3, retry.path("attempt").asInt());
        interrupted = claim(context, task);
        assertNotEquals(failedClaim.projectionGenerationId(), interrupted.projectionGenerationId());
        status(http, base, task, "processing");
        assertEquals(seed.publication(), active(database, seed.document()));
      }
      int before = calls(models, projection);
      try (var restored = start(models, projection)) {
        String base = base(restored);
        Path database = restored.getBean(SqliteAuthorityStore.class).libraryPath();
        assertEquals(before, calls(models, projection));
        var recovered = status(http, base, task, "failed");
        assertEquals("worker_interrupted", recovered.path("error_code").asString());
        assertEquals(seed.publication(), active(database, seed.document()));
        request(http, base, "GET", source, null, null, 200);
        assertFalse(
            restored.getBean(IndexingService.class).completeIndexing(interrupted, Map.of(), null));
        assertEquals(seed.publication(), active(database, seed.document()));
        request(http, base, "POST", "/v1/indexings/" + task + "/retry", null, null, 409);
        assertEquals(before, calls(models, projection));
        assertOriginal(http, base, seed.document());
        exercise(http, base, models, database, seed.document(), 1, INITIAL, INITIAL);
      }
    }
  }

  private record Seed(
      String document, String revision, String task, String publication, IndexClaim claim) {}

  private static Seed seed(
      ConfigurableApplicationContext context,
      HttpClient http,
      AnswerProtocolServer models,
      IndexingTestServer projection)
      throws Exception {
    String base = base(context);
    save(http, base, models, projection, 0, INITIAL, INITIAL, "fixture-v1", true);
    activate(http, base, models, projection, 1, 200);
    var uploaded =
        request(
            http,
            base,
            "POST",
            "/v1/documents?filename=reindex-policy.txt",
            TEXT.getBytes(StandardCharsets.UTF_8),
            "application/octet-stream",
            202);
    String document = uploaded.path("document_id").asString();
    var ingestion = context.getBean(IngestionTaskProcessor.class);
    var parseClaim = ingestion.claim().orElseThrow();
    assertEquals(document, parseClaim.documentId());
    try (var lease = context.getBean(SqliteAuthorityStore.class).operationGate().enter()) {
      ingestion.process(parseClaim);
    }
    var parsed =
        request(
            http,
            base,
            "GET",
            "/v1/ingestions/" + uploaded.path("task_id").asString(),
            null,
            null,
            200);
    assertEquals("parsed", parsed.path("state").asString());
    var queued =
        request(http, base, "POST", "/v1/documents/" + document + "/index", null, null, 202);
    String task = queued.path("task_id").asString();
    var claim = claim(context, task);
    process(context, claim);
    var published = status(http, base, task, "indexed");
    int beforeEntryReads = calls(models, projection);
    var config = request(http, base, "GET", "/v1/config", null, null, 200);
    assertTrue(config.path("capabilities").isArray());
    var capabilities = new ArrayList<String>();
    for (var capability : config.path("capabilities")) {
      capabilities.add(capability.asString());
    }
    assertTrue(capabilities.contains("text_reindex"));
    assertReindexEntry(
        http,
        base,
        document,
        claim.revisionId(),
        published.path("index_publication_id").asString(),
        task,
        "indexed",
        true);
    assertEquals(beforeEntryReads, calls(models, projection));
    return new Seed(
        document,
        claim.revisionId(),
        task,
        published.path("index_publication_id").asString(),
        claim);
  }

  private static void assertReindexEntry(
      HttpClient http,
      String base,
      String documentId,
      String revisionId,
      String publicationId,
      String taskId,
      String taskState,
      boolean enabled)
      throws Exception {
    var page = request(http, base, "GET", "/v1/management/documents", null, null, 200);
    assertEquals(1, page.path("total").asInt());
    assertEquals(1, page.path("items").size());
    var document = page.path("items").get(0);
    assertEquals(documentId, document.path("document_id").asString());
    assertTrue(document.path("can_reindex").isBoolean());
    assertEquals(enabled, document.path("can_reindex").asBoolean());
    assertEquals(revisionId, document.path("active_revision_id").asString());
    assertEquals(publicationId, document.path("index_publication_id").asString());
    assertEquals(taskId, document.path("latest_index_job").path("task_id").asString());
    assertEquals(taskState, document.path("latest_index_job").path("state").asString());
  }

  private static IndexClaim claim(ConfigurableApplicationContext context, String expectedTask) {
    try (var lease = context.getBean(SqliteAuthorityStore.class).operationGate().enter()) {
      var claim =
          context.getBean(ManagedTextRuntime.class).capture().indexing().claim().orElseThrow();
      assertEquals(expectedTask, claim.jobId());
      return claim;
    }
  }

  private static void process(ConfigurableApplicationContext context, IndexClaim claim) {
    try (var lease = context.getBean(SqliteAuthorityStore.class).operationGate().enter()) {
      context.getBean(ManagedTextRuntime.class).capture().indexing().process(claim);
    }
  }

  private static JsonNode status(HttpClient http, String base, String task, String expected)
      throws Exception {
    var result = request(http, base, "GET", "/v1/indexings/" + task, null, null, 200);
    assertEquals(expected, result.path("state").asString(), result.toString());
    return result;
  }

  private static JsonNode reindex(
      HttpClient http, String base, String document, String publication, int expected)
      throws Exception {
    return request(
        http,
        base,
        "POST",
        "/v1/documents/" + document + "/reindex",
        JSON.writeValueAsBytes(Map.of("base_publication_id", publication)),
        "application/json",
        expected);
  }

  private static int calls(AnswerProtocolServer models, IndexingTestServer projection) {
    return models.requests.size() + projection.requests.size();
  }

  private static int count(AnswerProtocolServer models, String path) {
    return (int) models.requests.stream().filter(request -> request.path().equals(path)).count();
  }

  private static String active(Path database, String document) throws Exception {
    return JSON.readTree(
            rows(
                database,
                "SELECT publication_id FROM active_corpus_publications WHERE document_id=?",
                document))
        .get(0)
        .get(0)
        .asString();
  }

  private static Map<String, String> history(Path database, Seed seed, String answer)
      throws Exception {
    var result = new LinkedHashMap<String, String>();
    result.put(
        "original",
        rows(
            database,
            "SELECT hex(original_blob),initial_revision_id,parsed_revision_id FROM corpus_documents WHERE document_id=?",
            seed.document()));
    for (String table : List.of("corpus_revisions", "corpus_pages", "corpus_segments")) {
      String column = table.equals("corpus_revisions") ? "id" : "revision_id";
      result.put(
          table,
          rows(
              database,
              "SELECT * FROM " + table + " WHERE " + column + "=? ORDER BY 1,2",
              seed.revision()));
    }
    result.put("old_job", rows(database, "SELECT * FROM indexing_jobs WHERE id=?", seed.task()));
    result.put(
        "old_attempt",
        rows(database, "SELECT * FROM indexing_attempts WHERE job_id=?", seed.task()));
    result.put(
        "old_publication",
        rows(database, "SELECT * FROM index_publications WHERE id=?", seed.publication()));
    result.put(
        "old_entries",
        rows(
            database,
            "SELECT * FROM index_publication_entries WHERE publication_id=? ORDER BY source_segment_id",
            seed.publication()));
    result.put("old_trace", rows(database, "SELECT * FROM query_traces WHERE id=?", answer));
    for (String table : List.of("query_trace_documents", "query_trace_evidence")) {
      result.put(
          table, rows(database, "SELECT * FROM " + table + " WHERE trace_id=? ORDER BY 2", answer));
    }
    for (var entry : result.entrySet()) {
      assertNotEquals("[]", entry.getValue(), entry.getKey());
    }
    return Map.copyOf(result);
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
    var context =
        application.run(
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
    context.getBean(IngestionJob.class).close();
    context.getBean(IndexingJob.class).close();
    return context;
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
}
