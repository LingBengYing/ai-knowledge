package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.job.DocumentCleanupJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.support.DocumentWithdrawal;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
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
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Real local HTTP control and authorization; physical execution is covered independently. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentCleanupHttpTest {
  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  void start() {
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
            "--rag.ingestion.enabled=false",
            "--rag.indexing.enabled=false",
            "--rag.answers.enabled=false",
            "--rag.document-removal.enabled=true",
            "--rag.document-cleanup.enabled=true");
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    // Freeze execution, not control: these HTTP assertions inspect accepted pending receipts.
    context.getBean(DocumentCleanupJob.class).close();
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    client.close();
  }

  @Test
  void legacyWithdrawalRemainsFourFieldsAndDoesNotAutomaticallyRequestCleanup() throws Exception {
    seed("legacy-cleanup");
    withdraw("legacy-cleanup");
    var current = request("GET", "/v1/documents/legacy-cleanup/cleanup", "owner", null);
    assertEquals(200, current.statusCode());
    var value = json.readTree(current.body());
    assertEquals(9, value.size());
    assertEquals("not_requested", value.path("cleanup_status").asString());
    assertTrue(value.path("cleanup_id").isNull());
    assertEquals(0, value.path("resources").size());
    assertEquals(
        404, request("GET", "/v1/documents/legacy-cleanup/cleanup", "reader", null).statusCode());
    assertEquals(
        200, request("GET", "/v1/documents/legacy-cleanup/cleanup", "editor", null).statusCode());
    assertEquals("private, no-store", current.headers().firstValue("cache-control").orElseThrow());
    assertTrue(current.headers().firstValue("x-request-id").isPresent());
  }

  @Test
  void singleControlHasStableIdentityAndDoesNotClaimCompletion() throws Exception {
    seed("single-cleanup");
    var first = request("POST", "/v1/documents/single-cleanup/cleanup", "owner", null);
    assertEquals(202, first.statusCode());
    var value = json.readTree(first.body());
    assertEquals(9, value.size());
    assertEquals("deleting", value.path("status").asString());
    assertEquals("pending", value.path("cleanup_status").asString());
    assertTrue(value.path("completed_at").isNull());
    assertEquals(9, value.path("resources").size());
    var repeat = request("POST", "/v1/documents/single-cleanup/cleanup", "editor", null);
    assertEquals(202, repeat.statusCode());
    assertEquals(value.path("cleanup_id"), json.readTree(repeat.body()).path("cleanup_id"));
    assertEquals(
        404, request("POST", "/v1/documents/single-cleanup/cleanup", "reader", null).statusCode());
    assertEquals(
        404, request("GET", "/v1/documents/single-cleanup/cleanup", "stranger", null).statusCode());
  }

  @Test
  void batchPreservesEveryItemAndBusyNeverWithdrawsLiveDocuments() throws Exception {
    seed("batch-cleanup");
    try (var actual = context.getBean(LibraryOperationGate.class).enter()) {
      var response =
          request(
              "POST",
              "/v1/management/document-cleanups",
              "owner",
              "{\"document_ids\":[\"batch-cleanup\",\"not-here\"]}");
      assertEquals(202, response.statusCode());
      var value = json.readTree(response.body());
      assertEquals(2, value.path("total").asInt());
      assertEquals("batch-cleanup", value.path("items").get(0).path("document_id").asString());
      assertEquals("busy", value.path("items").get(0).path("status").asString());
      assertEquals("document_busy", value.path("items").get(0).path("error_code").asString());
      assertEquals("not_found", value.path("items").get(1).path("status").asString());
      assertEquals(
          409, request("POST", "/v1/documents/batch-cleanup/cleanup", "owner", null).statusCode());
    }
    assertEquals(
        404, request("GET", "/v1/documents/batch-cleanup/cleanup", "owner", null).statusCode());
    var response =
        request(
            "POST",
            "/v1/management/document-cleanups",
            "owner",
            "{\"document_ids\":[\"batch-cleanup\",\"not-here\"]}");
    assertEquals(202, response.statusCode());
    assertEquals(
        "accepted", json.readTree(response.body()).path("items").get(0).path("status").asString());
  }

  @Test
  void strictControlInputsFailBeforeMutationAndPagingDoesNotLeakReaderRows() throws Exception {
    seed("invalid-cleanup");
    for (String suffix : java.util.List.of("?", "?x=1")) {
      assertEquals(422, bodylessControlStatus("/v1/documents/invalid-cleanup/cleanup" + suffix));
    }
    assertEquals(
        422, request("POST", "/v1/documents/invalid-cleanup/cleanup", "owner", "{}").statusCode());
    for (String body :
        java.util.List.of(
            "{}",
            "{\"document_ids\":[]}",
            "{\"document_ids\":[\"invalid-cleanup\",\"invalid-cleanup\"]}",
            "{\"document_ids\":[\"invalid-cleanup\"],\"action\":\"delete\"}")) {
      assertEquals(
          422, request("POST", "/v1/management/document-cleanups", "owner", body).statusCode());
    }
    assertEquals(
        404, request("GET", "/v1/documents/invalid-cleanup/cleanup", "owner", null).statusCode());
    for (String query :
        java.util.List.of("page=0", "page=1&page=2", "page_size=101", "owner=owner")) {
      assertEquals(
          422,
          request("GET", "/v1/management/document-cleanups?" + query, "owner", null).statusCode());
    }
    var reader =
        request("GET", "/v1/management/document-cleanups?page=1&page_size=20", "reader", null);
    assertEquals(200, reader.statusCode());
    assertEquals(0, json.readTree(reader.body()).path("total").asInt());
    assertEquals(0, json.readTree(reader.body()).path("items").size());
  }

  @Test
  void maintenanceKeepsAuthenticatedStatusReadableButClosesOrdinaryBodies() throws Exception {
    seed("maintenance-cleanup");
    withdraw("maintenance-cleanup");
    try (var maintenance =
        context.getBean(LibraryOperationGate.class).tryMaintenance().orElseThrow()) {
      assertEquals(503, request("GET", "/v1/management/documents", "owner", null).statusCode());
      assertEquals(200, request("GET", "/v1/config", "owner", null).statusCode());
      assertEquals(
          200,
          request("GET", "/v1/documents/maintenance-cleanup/cleanup", "owner", null).statusCode());
      assertEquals(
          404,
          request("GET", "/v1/documents/maintenance-cleanup/cleanup", "reader", null).statusCode());
      assertEquals(
          200, request("GET", "/v1/management/document-cleanups", "owner", null).statusCode());
    }
  }

  @Test
  void cleanupCapabilityIsIndependentOfOldHardDeleteAndReindex() throws Exception {
    seed("cap-cleanup");
    var runtime = json.readTree(request("GET", "/v1/config", "owner", null).body());
    assertTrue(runtime.path("capabilities").toString().contains("\"document_cleanup\""));
    assertTrue(runtime.path("unavailable").toString().contains("\"document_delete\""));
    var rows =
        json.readTree(
            request("GET", "/v1/management/documents?q=cap-cleanup", "owner", null).body());
    assertFalse(rows.path("items").get(0).path("can_delete").asBoolean());
    assertEquals(
        501,
        request(
                "POST",
                "/v1/management/document-actions",
                "owner",
                "{\"document_ids\":[\"cap-cleanup\"],\"action\":\"delete\"}")
            .statusCode());
  }

  private void seed(String id) {
    context
        .getBean(ManagementService.class)
        .registerSyntheticDocument(
            new Actor("org-main", "owner"),
            new SyntheticDocument(
                id,
                id + ".pdf",
                "document",
                "application/pdf",
                id + "-revision",
                "a".repeat(64),
                50),
            Map.of("reader", "reader", "editor", "editor"));
  }

  private int bodylessControlStatus(String path) throws Exception {
    // JDK HttpClient omits an empty query delimiter; preserve the exact HTTP request-target.
    URI endpoint = URI.create(base);
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), 5000);
      socket.setSoTimeout(5000);
      String raw =
          "POST "
              + path
              + " HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + endpoint.getPort()
              + "\r\n"
              + "Accept: application/json\r\n"
              + "X-Workspace-Id: org-main\r\n"
              + "X-Principal-Id: owner\r\n"
              + "Content-Length: 0\r\n"
              + "Connection: close\r\n\r\n";
      socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String status = reader.readLine();
      assertTrue(status != null && status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"));
      return Integer.parseInt(status.substring(9, 12));
    }
  }

  private HttpResponse<String> request(String method, String path, String principal, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", principal);
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Legacy tombstone written by the removed single-document withdrawal endpoint. */
  private void withdraw(String document) {
    DocumentWithdrawal.withdraw(
        context.getBean(SqliteAuthorityStore.class), new Actor("org-main", "owner"), document);
  }
}
