package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
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

/**
 * One normal HTTP journey with real parser/indexer JVMs and explicit local protocol fixtures. This
 * is application wiring evidence, not real model/Milvus quality or production acceptance.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextMainlineHttpTest {
  private static final String FILENAME = "星河制造差旅政策.pdf";
  private static final String PDF_SHA256 =
      "7baf4e2206a9779b59a6252217c86e610897d8e6980e06b1f05a512732f46752";
  private static final String EXPECTED_QUOTE = "上海住宿标准为每晚650元";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir static Path directory;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private IndexingTestServer indexing;
  private AnswerProtocolServer answers;
  private ConfigurableApplicationContext context;
  private String base;

  @BeforeAll
  void start() throws Exception {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
    indexing = new IndexingTestServer();
    answers = new AnswerProtocolServer();
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
    context =
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
            "--rag.document-removal.enabled=false",
            "--rag.ingestion.parse-timeout-ms=15000",
            "--rag.indexing.timeout-ms=15000",
            "--rag.answers.timeout-ms=15000");
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    assertEquals(1, context.getBeansOfType(SqliteAuthorityStore.class).size());
    assertEquals(1, context.getBeansOfType(TextAdapterSettings.class).size());
    assertEquals(1, context.getBeansOfType(IngestionJob.class).size());
    assertEquals(1, context.getBeansOfType(IndexingJob.class).size());
    assertTrue(indexing.requests.isEmpty());
    assertTrue(answers.requests.isEmpty());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    if (indexing != null) {
      indexing.close();
    }
    if (answers != null) {
      answers.close();
    }
    http.close();
  }

  @Test
  void fixedPdfTravelsThroughUploadParseIndexAnswerAndSourceOverHttp() throws Exception {
    byte[] pdf;
    try (var input = getClass().getResourceAsStream("/corpus/" + FILENAME)) {
      assertNotNull(input, "The original fixed PDF fixture must remain available");
      pdf = input.readAllBytes();
    }
    assertEquals(
        PDF_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)));
    var uploaded =
        jsonRequest(
            "POST",
            "/v1/documents?filename=" + URLEncoder.encode(FILENAME, StandardCharsets.UTF_8),
            pdf,
            "application/octet-stream",
            202);
    String documentId = uploaded.path("document_id").asString();
    String revisionId = uploaded.path("revision_id").asString();
    String ingestionId = uploaded.path("task_id").asString();
    assertFalse(documentId.isEmpty());
    assertFalse(revisionId.isEmpty());
    assertFalse(ingestionId.isEmpty());
    var parsed = awaitTask("ingestions", ingestionId, "parsed");
    assertEquals(documentId, parsed.path("document_id").asString());
    assertEquals(revisionId, parsed.path("revision_id").asString());
    var parsedRow = onlyDocument();
    assertEquals(documentId, parsedRow.path("document_id").asString());
    assertEquals("parsed", parsedRow.path("status").asString());
    assertTrue(parsedRow.path("segment_count").asInt() > 0);
    assertFalse(parsedRow.path("synthetic_fixture").asBoolean());
    assertTrue(parsedRow.path("active_revision_id").isNull());

    var queuedIndex =
        jsonRequest("POST", "/v1/documents/" + documentId + "/index", null, null, 202);
    var indexed = awaitTask("indexings", queuedIndex.path("task_id").asString(), "indexed");
    assertEquals(documentId, indexed.path("document_id").asString());
    assertEquals(revisionId, indexed.path("revision_id").asString());
    var indexedRow = onlyDocument();
    assertEquals("indexed", indexedRow.path("index_status").asString());
    assertEquals(revisionId, indexedRow.path("active_revision_id").asString());
    assertEquals(indexed.path("index_publication_id"), indexedRow.path("index_publication_id"));
    assertFalse(indexing.committedUpserts.isEmpty(), "The real index worker must write the rows");

    var answer =
        jsonRequest(
            "POST",
            "/v1/answers",
            JSON.writeValueAsBytes(
                Map.of("question", "上海住宿标准是多少？", "document_ids", List.of(documentId))),
            "application/json",
            200);
    assertEquals("answered", answer.path("status").asString(), answer.toString());
    assertTrue(answer.path("answer").asString().contains("650"));
    assertFalse(answer.path("citations").isEmpty());
    var citation = answer.path("citations").get(0);
    assertEquals(documentId, citation.path("document_id").asString());
    assertEquals(revisionId, citation.path("revision_id").asString());
    assertEquals(PDF_SHA256, citation.path("source_sha256").asString());
    assertEquals(FILENAME, citation.path("filename").asString());
    assertTrue(citation.path("quote").asString().contains(EXPECTED_QUOTE));
    String sourceUrl = citation.path("source_url").asString();
    assertEquals("/v1/sources/" + answer.path("answer_id").asString() + "/1", sourceUrl);
    var source = jsonRequest("GET", sourceUrl, null, null, 200);
    assertEquals(answer.path("answer_id"), source.path("answer_id"));
    assertEquals(citation, source.path("citation"));
    assertEquals(
        2,
        indexing.requests.stream()
            .filter(request -> request.path().endsWith("/entities/search"))
            .count());
    assertEquals(
        List.of("/rerank", "/chat/completions"),
        answers.requests.stream().map(AnswerProtocolServer.Request::path).toList());
  }

  private JsonNode onlyDocument() throws Exception {
    var page = jsonRequest("GET", "/v1/management/documents", null, null, 200);
    assertEquals(1, page.path("total").asInt());
    assertEquals(1, page.path("items").size());
    return page.path("items").get(0);
  }

  private JsonNode awaitTask(String resource, String taskId, String expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      var task = jsonRequest("GET", "/v1/" + resource + "/" + taskId, null, null, 200);
      if (!List.of("queued", "processing").contains(task.path("state").asString())) {
        assertEquals(expected, task.path("state").asString(), task.toString());
        return task;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < deadline);
    return fail("The normal " + resource + " task did not finish");
  }

  private JsonNode jsonRequest(
      String method, String path, byte[] content, String contentType, int expectedStatus)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "mainline-owner")
            .header("Origin", base);
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    var response =
        http.send(
            request
                .method(
                    method,
                    content == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
