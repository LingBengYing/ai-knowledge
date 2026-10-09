package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.support.DocumentFormatFixtures;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real upload, parser child JVM and SQLite; no model or indexing Module is configured. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DocumentFormatsHttpTest {
  private static final Map<String, String> MEDIA_TYPES =
      Map.ofEntries(
          Map.entry("pdf", "application/pdf"),
          Map.entry("properties", "text/x-java-properties"),
          Map.entry("html", "text/html"),
          Map.entry("vtt", "text/vtt"),
          Map.entry("csv", "text/csv"),
          Map.entry("msg", "application/vnd.ms-outlook"),
          Map.entry("markdown", "text/markdown"),
          Map.entry("eml", "message/rfc822"),
          Map.entry("ppt", "application/vnd.ms-powerpoint"),
          Map.entry(
              "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
          Map.entry("doc", "application/msword"),
          Map.entry("txt", "text/plain"),
          Map.entry(
              "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
          Map.entry("mdx", "text/markdown"),
          Map.entry("xls", "application/vnd.ms-excel"),
          Map.entry("odt", "application/vnd.oasis.opendocument.text"),
          Map.entry("md", "text/markdown"),
          Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
          Map.entry("xml", "application/xml"),
          Map.entry("epub", "application/epub+zip"),
          Map.entry("htm", "text/html"));

  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  void start() {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    context =
        application.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=development_headers",
            "--rag.data-directory=" + directory,
            "--rag.ingestion.enabled=true",
            "--rag.ingestion.parse-timeout-ms=60000",
            "--rag.indexing.enabled=false",
            "--rag.answers.enabled=false",
            "--rag.knowledge-agent.enabled=false");
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
    assertTrue(context.getBeansOfType(RetrievalProjection.class).isEmpty());
    assertTrue(context.getBeansOfType(IndexingJob.class).isEmpty());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    client.close();
  }

  static Stream<Arguments> formats() throws Exception {
    var samples = DocumentFormatFixtures.samples();
    assertEquals(21, samples.size());
    var extensions =
        samples.keySet().stream()
            .map(DocumentFormatsHttpTest::extension)
            .collect(Collectors.toSet());
    assertEquals(MEDIA_TYPES.keySet(), extensions);
    return samples.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
  }

  @ParameterizedTest(name = "HTTP upload parses and preserves {0}")
  @MethodSource("formats")
  void everySupportedFormatUploadsParsesAndPreservesItsOriginal(String sampleName, byte[] bytes)
      throws Exception {
    String ext = extension(sampleName);
    String filename = "format-" + UUID.randomUUID() + "." + ext.toUpperCase(Locale.ROOT);
    var task = uploadAndParse(filename, bytes);
    assertParsedDocument(task, filename);
    assertOriginal(task, filename, ext, bytes);
  }

  @Test
  void savedOfficeOriginalAndParsedStateSurviveRestartWithoutModels() throws Exception {
    var sample =
        DocumentFormatFixtures.samples().entrySet().stream()
            .filter(entry -> extension(entry.getKey()).equals("docx"))
            .findFirst()
            .orElseThrow();
    String filename = "restart-" + UUID.randomUUID() + ".docx";
    var task = uploadAndParse(filename, sample.getValue());
    assertParsedDocument(task, filename);
    context.close();
    context = null;
    start();
    var reloaded = successfulJson(get("/v1/ingestions/" + task.path("task_id").asString()));
    assertEquals("parsed", reloaded.path("state").asString(), reloaded.toString());
    assertEquals(task.path("revision_id").asString(), reloaded.path("revision_id").asString());
    assertParsedDocument(task, filename);
    assertOriginal(task, filename, "docx", sample.getValue());
  }

  @Test
  void binaryOfficeSuffixDoesNotTurnPlainTextIntoSuccessfulEvidence() throws Exception {
    var response =
        request(
            "POST",
            "/v1/documents?filename=invalid-" + UUID.randomUUID() + ".docx",
            "This is not an Office archive.".getBytes(StandardCharsets.UTF_8));
    if (response.statusCode() == 202) {
      var task = waitForTerminal(json.readTree(response.body()).path("task_id").asString());
      assertEquals("failed", task.path("state").asString(), task.toString());
      assertFalse(task.path("error_code").asString().isBlank());
    } else {
      assertEquals(422, response.statusCode(), text(response));
      assertFalse(json.readTree(response.body()).path("error_code").asString().isBlank());
    }
  }

  private JsonNode uploadAndParse(String filename, byte[] bytes) throws Exception {
    var response = request("POST", "/v1/documents?filename=" + encode(filename), bytes);
    assertEquals(202, response.statusCode(), text(response));
    var task = json.readTree(response.body());
    assertFalse(task.path("task_id").asString().isBlank());
    assertEquals(filename, task.path("filename").asString());
    var completed = waitForTerminal(task.path("task_id").asString());
    assertEquals("parsed", completed.path("state").asString(), completed.toString());
    assertEquals(task.path("revision_id").asString(), completed.path("revision_id").asString());
    assertEquals(1, completed.path("attempt").asInt());
    return completed;
  }

  private JsonNode waitForTerminal(String taskId) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(65).toNanos();
    JsonNode task;
    do {
      task = successfulJson(get("/v1/ingestions/" + taskId));
      if (Set.of("parsed", "failed", "cancelled").contains(task.path("state").asString())) {
        return task;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < until);
    fail("Parser task did not reach a terminal state: " + task);
    return task;
  }

  private void assertParsedDocument(JsonNode task, String filename) throws Exception {
    var list = successfulJson(get("/v1/management/documents?q=" + encode(filename)));
    assertEquals(1, list.path("total").asInt(), list.toString());
    var document = list.path("items").get(0);
    assertEquals(task.path("document_id").asString(), document.path("document_id").asString());
    assertEquals("document", document.path("document_type").asString());
    assertEquals("parsed", document.path("status").asString(), document.toString());
    assertTrue(document.path("segment_count").asInt() > 0, document.toString());
    assertFalse(document.path("can_answer").asBoolean());
    assertTrue(document.path("active_revision_id").isNull());
  }

  private void assertOriginal(JsonNode task, String filename, String ext, byte[] bytes)
      throws Exception {
    String documentId = task.path("document_id").asString();
    String revisionId = task.path("revision_id").asString();
    var metadata = successfulJson(get("/v1/documents/" + documentId + "/original"));
    assertEquals(documentId, metadata.path("document_id").asString());
    assertEquals(revisionId, metadata.path("revision_id").asString());
    assertEquals(filename, metadata.path("filename").asString());
    assertEquals("document", metadata.path("document_type").asString());
    assertEquals(MEDIA_TYPES.get(ext), metadata.path("media_type").asString());
    assertEquals(ModelValues.sha256(bytes), metadata.path("source_sha256").asString());
    assertEquals(bytes.length, metadata.path("size_bytes").asInt());
    String contentPath = "/v1/documents/" + documentId + "/revisions/" + revisionId + "/content";
    assertEquals(contentPath, metadata.path("content_url").asString());
    var content = get(contentPath);
    assertEquals(200, content.statusCode(), text(content));
    assertEquals(MEDIA_TYPES.get(ext), content.headers().firstValue("content-type").orElseThrow());
    assertEquals("nosniff", content.headers().firstValue("x-content-type-options").orElseThrow());
    assertEquals("private, no-store", content.headers().firstValue("cache-control").orElseThrow());
    if (!Set.of("pdf", "txt", "md", "markdown", "mdx").contains(ext)) {
      assertTrue(
          content.headers().firstValue("content-disposition").orElse("").startsWith("attachment"),
          "New document formats must be downloaded, not executed in the application's origin");
    }
    assertArrayEquals(bytes, content.body());
  }

  private HttpResponse<byte[]> get(String path) throws Exception {
    return request("GET", path, null);
  }

  private HttpResponse<byte[]> request(String method, String path, byte[] content)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(15))
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "format-owner");
    if (content != null) {
      request.header("Content-Type", "application/octet-stream").header("Origin", base);
    }
    return client.send(
        request
            .method(
                method,
                content == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(content))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private JsonNode successfulJson(HttpResponse<byte[]> response) {
    assertEquals(200, response.statusCode(), text(response));
    return json.readTree(response.body());
  }

  private static String extension(String filename) {
    return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String text(HttpResponse<byte[]> response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }
}
