package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.web.converter.ManagementRequestMapper;
import com.evidence.rag.web.converter.ManagementResponseMapper;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexingHttpTest {
  @TempDir static Path directory;
  private IndexingTestServer external;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private final Actor owner = new Actor("org-main", "owner");

  @BeforeAll
  void start() throws Exception {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
    external = new IndexingTestServer();
    var defaults = new LinkedHashMap<String, Object>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      defaults.put("RAG_" + kind + "_BASE_URL", external.endpoint().toString());
      defaults.put("RAG_" + kind + "_MODEL", "fixture-model");
      defaults.put("RAG_" + kind + "_API_KEY", "synthetic-model-credential");
    }
    defaults.put("RAG_EMBEDDING_DIMENSIONS", "2");
    defaults.put("RAG_EMBEDDING_REVISION", "fixture-v1");
    defaults.put("RAG_MILVUS_ENDPOINT", external.endpoint().toString());
    defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    defaults.put("RAG_MILVUS_COLLECTION", external.settings().projection().collection());
    defaults.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    defaults.put("RAG_TEXT_DEADLINE_MS", "10000");
    var app = new SpringApplication(RagApplication.class);
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    app.setEnvironment(environment);
    app.setDefaultProperties(defaults);
    context =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=development_headers",
            "--rag.data-directory=" + directory,
            "--rag.ingestion.enabled=false",
            "--rag.indexing.enabled=true",
            "--rag.indexing.timeout-ms=15000");
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    if (external != null) {
      external.close();
    }
  }

  private HttpResponse<String> request(String method, String path, String principal, String content)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    if (principal != null) {
      builder.header("X-Workspace-Id", "org-main").header("X-Principal-Id", principal);
    }
    builder.header("Origin", base);
    return client.send(
        builder
            .method(
                method,
                content == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(content))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String parsed(String name) {
    var authority = context.getBean(IngestionService.class);
    var content = "合成政策：上海住宿上限650元。\n中文😀定位。".getBytes(StandardCharsets.UTF_8);
    var upload = authority.uploadDocument(owner, name, "text/plain", content);
    var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(claim, new TextParser().parse(name, "text/plain", content)));
    return upload.documentId();
  }

  private JsonNode awaitTerminal(String taskId) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    JsonNode task;
    do {
      var result = request("GET", "/v1/indexings/" + taskId, "owner", null);
      assertEquals(200, result.statusCode());
      task = json.readTree(result.body());
      if (!List.of("queued", "processing").contains(task.path("state").asString())) {
        return task;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < end);
    fail("indexing task did not finish: " + task.path("state").asString());
    return task;
  }

  @Test
  void runtimeAdvertisesOnlyExplicitIndexingWithoutPretendingAnswerReadiness() throws Exception {
    var result = request("GET", "/v1/config", null, null);
    assertEquals(200, result.statusCode());
    var config = json.readTree(result.body());
    assertEquals("text_indexing", config.path("migration_stage").asString());
    var capabilities = config.path("capabilities");
    assertTrue(capabilities.toString().contains("\"text_index\""));
    assertTrue(capabilities.toString().contains("\"indexings\""));
    assertFalse(capabilities.toString().contains("text_upload"));
    assertFalse(capabilities.toString().contains("answers"));
    assertTrue(config.path("unavailable").toString().contains("answers"));
    assertFalse(result.body().contains("synthetic-model-credential"));
    assertFalse(result.body().contains("synthetic-projection-credential"));
    assertEquals(503, request("GET", "/health/ready", null, null).statusCode());
  }

  @Test
  void authorizedIndexingRunsRealChildAndPublishesOnlyCompleteEvidence() throws Exception {
    String document = parsed("index-http-success.txt");
    var created = request("POST", "/v1/documents/" + document + "/index", "owner", null);
    assertEquals(202, created.statusCode(), created.body());
    var task = awaitTerminal(json.readTree(created.body()).path("task_id").asString());
    assertEquals("indexed", task.path("state").asString(), task.toString());
    var authority = context.getBean(IngestionService.class);
    var page = managementPage(owner, Map.of("q", "index-http-success"));
    @SuppressWarnings("unchecked")
    var row = ((List<Map<String, Object>>) page.get("items")).getFirst();
    assertEquals("parsed", row.get("status"));
    assertEquals("indexed", row.get("index_status"));
    assertEquals(task.path("revision_id").asString(), row.get("active_revision_id"));
    assertNotNull(row.get("index_publication_id"));
    assertEquals(false, row.get("can_answer"));
    assertEquals(
        409, request("POST", "/v1/documents/" + document + "/index", "owner", null).statusCode());
    assertEquals(
        404,
        request("GET", "/v1/indexings/" + task.path("task_id").asString(), "stranger", null)
            .statusCode());
    assertEquals(503, request("GET", "/health/ready", null, null).statusCode());
    assertEquals(404, request("POST", "/v1/answers", "owner", null).statusCode());
    assertTrue(
        external.requests.stream().anyMatch(item -> item.path().endsWith("/entities/query")));
    assertFalse(
        external.requests.stream()
            .anyMatch(item -> item.path().contains("rerank") || item.path().contains("chat")));
  }

  @Test
  void indexActionsRejectBodyQueryAndUnauthorizedRequestsBeforeSideEffects() throws Exception {
    String document = parsed("index-http-envelope.txt");
    String path = "/v1/documents/" + document + "/index";
    assertEquals(404, request("POST", path, "stranger", null).statusCode());
    assertEquals(422, request("POST", path + "?extra=1", "owner", null).statusCode());
    assertEquals(422, request("POST", path, "owner", "{}").statusCode());
    assertEquals(422, request("GET", "/v1/indexings/missing?extra=1", "owner", null).statusCode());
    assertEquals(422, request("POST", "/v1/indexings/missing/retry", "owner", "{}").statusCode());
    assertEquals(
        422, request("POST", "/v1/indexings/missing/cancel?x=1", "owner", null).statusCode());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> managementPage(Actor actor, Map<String, String> query) {
    var result =
        context
            .getBean(ManagementService.class)
            .listDocuments(actor, ManagementRequestMapper.query(query));
    return json.convertValue(ManagementResponseMapper.page(result), Map.class);
  }
}
