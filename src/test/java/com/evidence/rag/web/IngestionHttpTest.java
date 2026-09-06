package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.RagApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionHttpTest {
  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  void start() {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
    context =
        SpringApplication.run(
            RagApplication.class,
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=development_headers",
            "--rag.data-directory=" + directory,
            "--rag.ingestion.enabled=true",
            "--rag.ingestion.upload-timeout-ms=1000");
    assertEquals(
        directory, context.getBean(com.evidence.rag.config.RagProperties.class).dataDirectory());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
  }

  private HttpResponse<String> request(
      String method, String path, String principal, byte[] content, Map<String, String> extra)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
    if (principal != null)
      builder.header("X-Workspace-Id", "org-main").header("X-Principal-Id", principal);
    extra.forEach(builder::header);
    return client.send(
        builder
            .method(
                method,
                content == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(content))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> result) {
    return json.readTree(result.body());
  }

  @Test
  void realUploadBecomesDurableParsedEvidenceButNotQueryable() throws Exception {
    var upload =
        request(
            "POST",
            "/v1/documents?filename=policy.txt",
            "uploader",
            "合成政策：上海住宿上限650元。\n中文😀定位。".getBytes(StandardCharsets.UTF_8),
            Map.of("Content-Type", "application/octet-stream", "Origin", base));
    assertEquals(202, upload.statusCode(), upload.body());
    var task = body(upload);
    String taskId = task.path("task_id").asString();
    assertFalse(taskId.isEmpty());
    assertEquals("queued", task.path("state").asString());
    long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    do {
      task = body(request("GET", "/v1/ingestions/" + taskId, "uploader", null, Map.of()));
      if (task.path("state").asString().equals("parsed")) break;
      Thread.sleep(30);
    } while (System.nanoTime() < until);
    assertEquals("parsed", task.path("state").asString(), task.toString());
    assertFalse(task.path("can_retry").asBoolean());
    var list =
        body(request("GET", "/v1/management/documents?status=parsed", "uploader", null, Map.of()));
    assertEquals(1, list.path("total").asInt());
    var doc = list.path("items").get(0);
    assertFalse(doc.path("synthetic_fixture").asBoolean());
    assertTrue(doc.path("active_revision_id").isNull());
    assertFalse(doc.path("can_answer").asBoolean());
    assertTrue(doc.path("segment_count").asInt() > 0);
    assertEquals(
        404, request("GET", "/v1/ingestions/" + taskId, "stranger", null, Map.of()).statusCode());
    assertEquals(
        404,
        request("POST", "/v1/ingestions/" + taskId + "/cancel", "stranger", null, Map.of())
            .statusCode());
    assertEquals(
        409,
        request("POST", "/v1/ingestions/" + taskId + "/retry", "uploader", null, Map.of())
            .statusCode());
    assertEquals(503, request("GET", "/health/ready", null, null, Map.of()).statusCode());
    assertEquals(404, request("POST", "/v1/answers", "uploader", null, Map.of()).statusCode());
  }

  @Test
  void envelopeAuthenticationAndOriginFailBeforeCreatingDocuments() throws Exception {
    var bytes = "synthetic".getBytes(StandardCharsets.UTF_8);
    var headers = Map.of("Content-Type", "application/octet-stream");
    assertEquals(
        422, request("POST", "/v1/documents?filename=x.txt", null, bytes, headers).statusCode());
    assertEquals(
        403,
        request(
                "POST",
                "/v1/documents?filename=x.txt",
                "invalid",
                bytes,
                Map.of(
                    "Content-Type", "application/octet-stream", "Origin", "https://other.invalid"))
            .statusCode());
    for (String query :
        new String[] {
          "",
          "?filename=x.txt&filename=y.txt",
          "?filename=x.txt&extra=1",
          "?filename=..%2Fx.txt",
          "?filename=x.exe"
        }) {
      assertEquals(
          422, request("POST", "/v1/documents" + query, "invalid", bytes, headers).statusCode());
    }
    assertEquals(
        415,
        request(
                "POST",
                "/v1/documents?filename=x.txt",
                "invalid",
                bytes,
                Map.of("Content-Type", "application/json"))
            .statusCode());
    assertEquals(
        422,
        request("POST", "/v1/documents?filename=x.txt", "invalid", new byte[0], headers)
            .statusCode());
    assertEquals(
        413,
        request(
                "POST",
                "/v1/documents?filename=x.txt",
                "invalid",
                new byte[20 * 1024 * 1024 + 1],
                headers)
            .statusCode());
    assertEquals(
        0,
        body(request("GET", "/v1/management/documents", "invalid", null, Map.of()))
            .path("total")
            .asInt());
  }

  private Socket partialUpload(String principal) throws Exception {
    var uri = URI.create(base);
    var socket = new Socket(uri.getHost(), uri.getPort());
    socket.setSoTimeout(5000);
    socket
        .getOutputStream()
        .write(
            ("POST /v1/documents?filename=slow.txt HTTP/1.1\r\nHost: "
                    + uri.getHost()
                    + ":"
                    + uri.getPort()
                    + "\r\nX-Workspace-Id: org-main\r\nX-Principal-Id: "
                    + principal
                    + "\r\nContent-Type: application/octet-stream\r\nContent-Length: 8\r\n\r\nx")
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
    return socket;
  }

  @Test
  void incompleteUploadExpiresWithoutPersistingAnyDocumentAndCapacityRecovers() throws Exception {
    try (var socket = partialUpload("slow-owner")) {
      var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      assertTrue(reader.readLine().contains("408"));
    }
    assertEquals(
        0,
        body(request("GET", "/v1/management/documents", "slow-owner", null, Map.of()))
            .path("total")
            .asInt());
    var retry =
        request(
            "POST",
            "/v1/documents?filename=retry.txt",
            "slow-owner",
            "synthetic retry".getBytes(StandardCharsets.UTF_8),
            Map.of("Content-Type", "application/octet-stream"));
    assertEquals(202, retry.statusCode(), retry.body());
  }

  @Test
  void unfinishedConcurrentUploadsHaveAnAdmissionLimit() throws Exception {
    try (var first = partialUpload("quota-owner");
        var second = partialUpload("quota-owner")) {
      Thread.sleep(100);
      var denied =
          request(
              "POST",
              "/v1/documents?filename=third.txt",
              "quota-owner",
              "x".getBytes(StandardCharsets.UTF_8),
              Map.of("Content-Type", "application/octet-stream"));
      assertEquals(429, denied.statusCode(), denied.body());
      assertTrue(
          new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))
              .readLine()
              .contains("408"));
      assertTrue(
          new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8))
              .readLine()
              .contains("408"));
    }
    assertEquals(
        0,
        body(request("GET", "/v1/management/documents", "quota-owner", null, Map.of()))
            .path("total")
            .asInt());
  }

  @Test
  void taskActionsRejectUnexpectedBodyQueryAndMethods() throws Exception {
    assertEquals(405, request("GET", "/v1/documents", "owner", null, Map.of()).statusCode());
    assertEquals(
        422,
        request("GET", "/v1/ingestions/missing?extra=1", "owner", null, Map.of()).statusCode());
    assertEquals(
        422,
        request(
                "POST",
                "/v1/ingestions/missing/retry",
                "owner",
                "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json"))
            .statusCode());
    var config = request("GET", "/v1/config", null, null, Map.of());
    assertTrue(config.body().contains("text_upload"));
    assertTrue(config.body().contains("ingestions"));
    assertTrue(config.body().contains("text_ingestion"));
  }

  private JsonNode terminalTask(String taskId, String principal) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode task;
    do {
      task = body(request("GET", "/v1/ingestions/" + taskId, principal, null, Map.of()));
      if (!java.util.Set.of("queued", "processing").contains(task.path("state").asString()))
        return task;
      Thread.sleep(30);
    } while (System.nanoTime() < until);
    fail("Task did not reach a terminal state");
    return task;
  }

  @Test
  void invalidUtf8FailsSafelyAndExplicitRetriesStopAtThreeAttempts() throws Exception {
    var result =
        request(
            "POST",
            "/v1/documents?filename=invalid.txt",
            "failure-owner",
            new byte[] {(byte) 0xc3},
            Map.of("Content-Type", "application/octet-stream"));
    assertEquals(202, result.statusCode(), result.body());
    String id = body(result).path("task_id").asString();
    for (int attempt = 1; attempt <= 3; attempt++) {
      var failed = terminalTask(id, "failure-owner");
      assertEquals("failed", failed.path("state").asString(), failed.toString());
      assertEquals("unsupported_document", failed.path("error_code").asString());
      assertEquals(attempt, failed.path("attempt").asInt());
      assertEquals(attempt < 3, failed.path("can_retry").asBoolean());
      var retry =
          request("POST", "/v1/ingestions/" + id + "/retry", "failure-owner", null, Map.of());
      assertEquals(attempt < 3 ? 200 : 409, retry.statusCode(), retry.body());
    }
    var doc =
        body(request(
                "GET", "/v1/management/documents?status=failed", "failure-owner", null, Map.of()))
            .path("items")
            .get(0);
    assertEquals(0, doc.path("segment_count").asInt());
    assertTrue(doc.path("active_revision_id").isNull());
  }
}
