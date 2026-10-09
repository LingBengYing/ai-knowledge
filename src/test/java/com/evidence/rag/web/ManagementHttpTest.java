package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.service.ManagementService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
class ManagementHttpTest {
  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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
            "--rag.data-directory=" + directory);
    assertEquals(
        directory, context.getBean(com.evidence.rag.config.RagProperties.class).dataDirectory());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    var module = context.getBean(ManagementService.class);
    module.registerSyntheticDocument(
        new Actor("org-main", "owner"),
        new SyntheticDocument(
            "fixture",
            "Original.pdf",
            "document",
            "application/pdf",
            "revision-1",
            "a".repeat(64),
            50),
        Map.of("reader", "reader", "editor", "editor"));
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
  }

  private HttpResponse<String> request(String method, String path, String principal, String body)
      throws Exception {
    return request(method, path, "org-main", principal, body);
  }

  private HttpResponse<String> request(
      String method, String path, String workspace, String principal, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    if (principal != null) {
      builder.header("X-Workspace-Id", workspace).header("X-Principal-Id", principal);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return client.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  @Test
  void runtimeIsAliveButNotProductionReady() throws Exception {
    assertEquals(200, request("GET", "/health/live", null, null).statusCode());
    var ready = request("GET", "/health/ready", null, null);
    assertEquals(503, ready.statusCode());
    assertEquals("migration_incomplete", body(ready).path("status").asString());
    var config = request("GET", "/v1/config", null, null);
    assertEquals("java", body(config).path("edition").asString());
    assertFalse(config.body().contains("secret"));
    assertEquals("private, no-store", config.headers().firstValue("cache-control").orElseThrow());
    assertTrue(config.headers().firstValue("x-request-id").isPresent());
    assertTrue(
        config
            .headers()
            .firstValue("content-security-policy")
            .orElseThrow()
            .contains("frame-ancestors 'none'"));
    assertEquals(404, request("POST", "/v1/answers", "owner", "{}").statusCode());
  }

  @Test
  void sharedMembersCanReadAndEditWhileLoginOrganizationAndValidationRemainEnforced()
      throws Exception {
    assertEquals(422, request("GET", "/v1/management/documents", null, null).statusCode());
    assertEquals(
        401, request("GET", "/v1/management/documents", "other-org", "owner", null).statusCode());
    assertEquals(
        1,
        body(request("GET", "/v1/management/documents", "stranger", null)).path("total").asInt());
    var reader = request("GET", "/v1/management/documents?page_size=1", "reader", null);
    assertEquals(200, reader.statusCode());
    assertEquals(1, body(reader).path("total").asInt());
    assertTrue(body(reader).path("items").get(0).path("can_edit").asBoolean());
    assertEquals(
        200,
        request(
                "PATCH",
                "/v1/management/documents/fixture",
                "reader",
                "{\"display_name\":\"共享编辑\"}")
            .statusCode());
    assertEquals(
        200,
        request(
                "PATCH",
                "/v1/management/documents/fixture",
                "stranger",
                "{\"display_name\":\"无旧ACL成员编辑\"}")
            .statusCode());
    assertEquals(
        401,
        request(
                "PATCH",
                "/v1/management/documents/fixture",
                "other-org",
                "owner",
                "{\"display_name\":\"不可跨组织编辑\"}")
            .statusCode());
    for (String invalid :
        List.of(
            "{",
            "[]",
            "null",
            "{}",
            "{\"extra\":1}",
            "{\"tags\":null}",
            "{\"display_name\":\"first\",\"display_name\":\"second\"}",
            "{\"display_name\":\"first\"} {\"display_name\":\"second\"}")) {
      var response = request("PATCH", "/v1/management/documents/fixture", "owner", invalid);
      assertEquals(422, response.statusCode(), "invalid body " + invalid);
      assertTrue(
          response
              .headers()
              .firstValue("content-type")
              .orElseThrow()
              .contains("application/problem+json"));
    }
    assertEquals(
        422, request("GET", "/v1/management/documents?page_size=101", "owner", null).statusCode());
    var constraints =
        context.getBean(JsonMapper.class).tokenStreamFactory().streamReadConstraints();
    assertEquals(262144, constraints.getMaxDocumentLength());
    assertEquals(20, constraints.getMaxNestingDepth());
    assertEquals(20000, constraints.getMaxTokenCount());
  }

  @Test
  void foldersMetadataAndPartialBatchWorkAsAnIntegratedFlow() throws Exception {
    var created = request("POST", "/v1/management/folders", "reader", "{\"name\":\"HTTP资料\"}");
    assertEquals(201, created.statusCode());
    var folderId = body(created).path("folder_id").asString();
    assertEquals(
        200,
        request("PATCH", "/v1/management/folders/" + folderId, "stranger", "{\"name\":\"HTTP整理\"}")
            .statusCode());
    var updated =
        request(
            "PATCH",
            "/v1/management/documents/fixture",
            "owner",
            json.writeValueAsString(
                Map.of("display_name", "HTTP标题", "folder_id", folderId, "tags", List.of("归档"))));
    assertEquals(200, updated.statusCode());
    assertEquals("Original.pdf", body(updated).path("filename").asString());
    assertEquals("revision-1", body(updated).path("registered_revision_id").asString());
    assertTrue(body(updated).path("active_revision_id").isNull());
    assertEquals(
        409, request("DELETE", "/v1/management/folders/" + folderId, "owner", null).statusCode());
    var batch =
        request(
            "POST",
            "/v1/management/document-actions",
            "stranger",
            "{\"document_ids\":[\"fixture\",\"missing\"],\"action\":\"tag\",\"tags\":[\"批量\"]}");
    assertEquals(200, batch.statusCode());
    assertTrue(body(batch).path("items").get(0).path("ok").asBoolean());
    assertFalse(body(batch).path("items").get(1).path("ok").asBoolean());
    assertEquals(
        1, body(request("GET", "/v1/management/folders", "reader", null)).path("items").size());
    assertEquals(
        2, body(request("GET", "/v1/management/tags", "reader", null)).path("items").size());
    assertEquals(
        200,
        request("PATCH", "/v1/management/documents/fixture", "owner", "{\"folder_id\":null}")
            .statusCode());
    assertEquals(
        200, request("DELETE", "/v1/management/folders/" + folderId, "reader", null).statusCode());
    assertEquals(
        422,
        request(
                "POST",
                "/v1/management/document-actions",
                "owner",
                "{\"document_ids\":[\"fixture\"],\"action\":\"reindex\"}")
            .statusCode());
    var unavailable =
        request(
            "POST",
            "/v1/management/document-actions",
            "owner",
            "{\"document_ids\":[\"fixture\"],\"action\":\"reindex\",\"base_publication_ids\":{\"fixture\":\"observed-publication\"}}");
    assertEquals(200, unavailable.statusCode());
    assertEquals(1, body(unavailable).path("items").size());
    var result = body(unavailable).path("items").get(0);
    assertEquals("fixture", result.path("document_id").asString());
    assertFalse(result.path("ok").asBoolean());
    assertEquals("indexing_unavailable", result.path("error_code").asString());
  }
}
