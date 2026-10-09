package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP, SQLite, jobs and indexing child; every remote protocol is a synthetic loopback
 * fixture.
 */
class VisualLibraryHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String OWNER = "visual-owner";
  private static final String QUESTION =
      "Name both shapes, their colors and their left-to-right order. Answer in English.";
  private static final String RECALL = "RECALL_ONLY_SYNTHETIC_MARKER: a green triangle.";
  private static final List<String> CLAIMS =
      List.of("The left shape is a blue circle.", "The right shape is a red square.");
  @TempDir static Path directory;
  private String base;
  private HttpClient http;

  @Test
  void noTextImageTravelsThroughVisualRecallPublicationAnswerAndAuthorizedOriginalReadback()
      throws Exception {
    assertNotNull(directory);
    byte[] image = VisualSyntheticFixture.image("png").content();
    String sourceSha = ModelValues.sha256(image);
    try (var indexing = new IndexingTestServer();
        var answers = new AnswerProtocolServer();
        var vision = new VisionServer();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      http = client;
      var defaults = new LinkedHashMap<String, Object>(answers.environment());
      defaults.put("RAG_EMBEDDING_BASE_URL", indexing.endpoint().toString());
      defaults.put("RAG_EMBEDDING_MODEL", "fixture-model");
      defaults.put("RAG_EMBEDDING_API_KEY", "synthetic-model-credential");
      defaults.put("RAG_MILVUS_ENDPOINT", indexing.endpoint().toString());
      defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
      defaults.put("RAG_MILVUS_COLLECTION", indexing.settings().projection().collection());
      defaults.put("RAG_TEXT_DEADLINE_MS", "10000");
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      var application = new SpringApplication(RagApplication.class);
      application.setEnvironment(environment);
      application.setDefaultProperties(defaults);
      Path data = directory.resolve("data");
      try (var context =
          application.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + data,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.answers.enabled=true",
              "--rag.document-removal.enabled=true",
              "--rag.image-ocr.enabled=false",
              "--rag.visual.enabled=true",
              "--rag.visual.base-url=" + vision.endpoint(),
              "--rag.visual.model=synthetic-vision-model",
              "--rag.visual.api-key=synthetic-visual-library-credential",
              "--rag.visual.deadline-ms=15000",
              "--rag.visual.max-response-bytes=262144",
              "--rag.visual.allow-loopback-http=true",
              "--rag.ingestion.parse-timeout-ms=15000",
              "--rag.indexing.timeout-ms=15000",
              "--rag.answers.timeout-ms=15000")) {
        assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var upload =
            json(
                "POST",
                "/v1/documents?filename=shapes.png",
                image,
                "application/octet-stream",
                202);
        String document = upload.path("document_id").asString();
        String revision = upload.path("revision_id").asString();
        await("ingestions", upload.path("task_id").asString(), "parsed");
        var queued = json("POST", "/v1/documents/" + document + "/index", null, null, 202);
        await("indexings", queued.path("task_id").asString(), "indexed");
        var answer =
            json(
                "POST",
                "/v1/visual-answers",
                JSON.writeValueAsBytes(
                    Map.of("question", QUESTION, "document_ids", List.of(document))),
                "application/json",
                200);
        assertEquals("answered", answer.path("status").asString(), answer.toString());
        assertEquals(String.join("\n", CLAIMS), answer.path("answer").asString());
        assertTrue(answer.path("reason").isNull());
        assertEquals(1, answer.path("citations").size());
        var citation = answer.path("citations").get(0);
        String answerId = answer.path("answer_id").asString();
        assertFalse(answerId.isBlank());
        assertEquals(1, citation.path("number").asInt());
        assertEquals("image_region", citation.path("kind").asString());
        assertEquals(document, citation.path("document_id").asString());
        assertEquals(revision, citation.path("revision_id").asString());
        assertEquals(sourceSha, citation.path("source_sha256").asString());
        assertTrue(citation.path("parser_revision").asString().startsWith("java-image-visual-v1:"));
        assertEquals("shapes.png", citation.path("filename").asString());
        assertEquals("image/png", citation.path("media_type").asString());
        assertEquals(640, citation.path("width").asInt());
        assertEquals(320, citation.path("height").asInt());
        assertEquals("normalized_xyxy", citation.path("coordinate_system").asString());
        assertEquals(4, citation.path("bbox").size());
        for (int coordinate = 0; coordinate < 4; coordinate++) {
          assertEquals(
              coordinate < 2 ? 0.0 : 1.0, citation.path("bbox").get(coordinate).asDouble());
        }
        assertTrue(citation.path("model_revision").asString().startsWith("java-vision-models-v1-"));
        assertEquals(
            VisualAssessmentService.POLICY_REVISION, citation.path("policy_revision").asString());
        String sourceUrl = "/v1/visual-sources/" + answerId + "/1";
        String contentUrl = sourceUrl + "/content";
        assertEquals(sourceUrl, citation.path("source_url").asString());
        assertEquals(contentUrl, citation.path("content_url").asString());
        var source = json("GET", sourceUrl, null, null, 200);
        assertEquals(answerId, source.path("answer_id").asString());
        assertEquals(citation, source.path("citation"));
        assertNoTextLocator(answer);
        assertNoTextLocator(source);
        var original = request("GET", contentUrl, null, null, OWNER);
        assertEquals(200, original.statusCode());
        assertArrayEquals(image, original.body());
        assertEquals(sourceSha, ModelValues.sha256(original.body()));
        assertEquals("image/png", original.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(
            original.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(
            "nosniff", original.headers().firstValue("X-Content-Type-Options").orElseThrow());

        assertEquals(3, vision.requests.size());
        assertTrue(vision.replies.isEmpty());
        for (int index = 0; index < vision.requests.size(); index++) {
          var request = vision.requests.get(index);
          var parts = request.path("messages").get(1).path("content");
          assertEquals(2, parts.size());
          assertEquals("text", parts.get(0).path("type").asString());
          assertEquals("image_url", parts.get(1).path("type").asString());
          String dataUrl = parts.get(1).path("image_url").path("url").asString();
          String prefix = "data:image/png;base64,";
          assertTrue(dataUrl.startsWith(prefix));
          assertArrayEquals(image, Base64.getDecoder().decode(dataUrl.substring(prefix.length())));
          assertEquals("high", parts.get(1).path("image_url").path("detail").asString());
          if (index > 0) {
            assertFalse(request.toString().contains(RECALL), "Caption must never be proof context");
            assertTrue(parts.get(0).path("text").asString().contains(QUESTION));
          }
        }
        String verification =
            vision
                .requests
                .get(2)
                .path("messages")
                .get(1)
                .path("content")
                .get(0)
                .path("text")
                .asString();
        CLAIMS.forEach(claim -> assertTrue(verification.contains(claim)));
        assertEquals(
            RECALL,
            indexing.requests.stream()
                .filter(r -> r.path().equals("/embeddings"))
                .findFirst()
                .orElseThrow()
                .body()
                .path("input")
                .get(0)
                .asString());
        assertEquals(1, answers.requests.size());
        assertEquals("/rerank", answers.requests.getFirst().path());
        assertEquals(
            RECALL, answers.requests.getFirst().body().path("documents").get(0).asString());
        assertStoredVisualOnly(data, answerId);
        var sharedSource = request("GET", sourceUrl, null, null, "another-owner");
        assertEquals(200, sharedSource.statusCode());
        assertEquals(source, JSON.readTree(sharedSource.body()));
        var sharedOriginal = request("GET", contentUrl, null, null, "another-owner");
        assertEquals(200, sharedOriginal.statusCode());
        assertArrayEquals(image, sharedOriginal.body());
        assertEquals(422, request("GET", sourceUrl, null, null, null).statusCode());
        assertEquals(422, request("GET", contentUrl, null, null, null).statusCode());
        assertEquals(401, request("GET", sourceUrl, null, null, "other-org", OWNER).statusCode());
        assertEquals(401, request("GET", contentUrl, null, null, "other-org", OWNER).statusCode());
        json("DELETE", "/v1/documents/" + document, null, null, 202);
        assertEquals(404, request("GET", sourceUrl, null, null, OWNER).statusCode());
        assertEquals(404, request("GET", contentUrl, null, null, OWNER).statusCode());
        assertEquals(404, request("GET", sourceUrl, null, null, "another-owner").statusCode());
        assertEquals(404, request("GET", contentUrl, null, null, "another-owner").statusCode());
        assertEquals(3, vision.requests.size(), "Source reads and removal must not call a model");
      }
    }
  }

  private static void assertStoredVisualOnly(Path data, String answerId) throws Exception {
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + data.resolve("java-library.db"), config.toProperties())) {
      for (var expected :
          Map.of(
                  "corpus_pages",
                  0,
                  "corpus_segments",
                  0,
                  "image_evidence",
                  1,
                  "index_publication_entries",
                  0,
                  "image_publication_entries",
                  1,
                  "query_traces",
                  1,
                  "query_trace_evidence",
                  0,
                  "image_trace_evidence",
                  1)
              .entrySet()) {
        try (var query = connection.createStatement();
            var rows = query.executeQuery("SELECT COUNT(*) FROM " + expected.getKey())) {
          assertTrue(rows.next());
          assertEquals(expected.getValue().intValue(), rows.getInt(1), expected.getKey());
        }
      }
      try (var query =
              connection.prepareStatement("SELECT recall_text,recall_sha256 FROM image_evidence");
          var rows = query.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(RECALL, rows.getString(1));
        assertEquals(
            ModelValues.sha256(RECALL.getBytes(StandardCharsets.UTF_8)), rows.getString(2));
      }
      try (var query = connection.prepareStatement("SELECT * FROM query_traces WHERE id=?")) {
        query.setString(1, answerId);
        try (var rows = query.executeQuery()) {
          assertTrue(rows.next());
          for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
            String value = rows.getString(column);
            if (value != null) {
              assertFalse(value.contains(RECALL));
              assertFalse(value.contains(QUESTION));
              CLAIMS.forEach(claim -> assertFalse(value.contains(claim)));
            }
          }
        }
      }
    }
  }

  private static void assertNoTextLocator(JsonNode node) {
    for (String field : List.of("page", "start", "end", "quote")) {
      assertFalse(node.has(field), "Image responses must not contain text locators");
    }
    for (var child : node) {
      assertNoTextLocator(child);
    }
  }

  private void await(String resource, String id, String state) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    while (System.nanoTime() < until) {
      var task = json("GET", "/v1/" + resource + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(task.path("state").asString())) {
        assertEquals(state, task.path("state").asString(), task.toString());
        return;
      }
      Thread.sleep(40);
    }
    fail("Synthetic visual task did not finish");
  }

  private JsonNode json(String method, String path, byte[] body, String type, int status)
      throws Exception {
    var response = request(method, path, body, type, OWNER);
    assertEquals(
        status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private HttpResponse<byte[]> request(
      String method, String path, byte[] body, String type, String actor) throws Exception {
    return request(method, path, body, type, "org-main", actor);
  }

  private HttpResponse<byte[]> request(
      String method, String path, byte[] body, String type, String workspace, String actor)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Origin", base);
    if (actor != null) {
      request.header("X-Workspace-Id", workspace).header("X-Principal-Id", actor);
    }
    if (type != null) {
      request.header("Content-Type", type);
    }
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static final class VisionServer implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final ArrayBlockingQueue<Map<String, Object>> replies = new ArrayBlockingQueue<>(3);
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    private VisionServer() throws IOException {
      replies.add(Map.of("recall_text", RECALL));
      replies.add(Map.of("refused", false, "claims", CLAIMS));
      replies.add(
          Map.of(
              "complete",
              true,
              "support",
              List.of(
                  Map.of("index", 0, "supported", true), Map.of("index", 1, "supported", true))));
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/chat/completions", this::reply);
      server.start();
    }

    private String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void reply(HttpExchange exchange) throws IOException {
      try (exchange) {
        if (!"POST".equals(exchange.getRequestMethod())
            || !"/v1/chat/completions".equals(exchange.getRequestURI().getPath())
            || !"Bearer synthetic-visual-library-credential"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        byte[] input = exchange.getRequestBody().readNBytes(1024 * 1024 + 1);
        if (input.length > 1024 * 1024) {
          exchange.sendResponseHeaders(413, -1);
          return;
        }
        requests.add(JSON.readTree(input));
        var value = replies.poll();
        if (value == null) {
          exchange.sendResponseHeaders(503, -1);
          return;
        }
        byte[] body =
            JSON.writeValueAsBytes(
                Map.of(
                    "choices",
                    List.of(
                        Map.of(
                            "index",
                            0,
                            "finish_reason",
                            "stop",
                            "message",
                            Map.of(
                                "role", "assistant", "content", JSON.writeValueAsString(value))))));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
