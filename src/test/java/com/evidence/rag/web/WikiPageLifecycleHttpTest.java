package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WikiPageLifecycleHttpTest {
  @TempDir static Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void deletionAndRestorePreserveHistoryAndOriginalAcrossRestartWithDualCas() throws Exception {
    assertNotNull(directory);
    String document;
    String original = "合成灯塔资料：按下启动按钮，确认绿灯常亮。";
    try (var corpus = new PublishedCorpusFixture(directory)) {
      document = corpus.publish(new Actor("org-main", "owner"), original).documentId();
    }
    String pageId;
    String sourcePath;
    try (var context = start()) {
      var proposal =
          json(request(context, "POST", "/v1/wiki/proposals", proposal(null, document), "member"));
      var accepted =
          request(
              context,
              "POST",
              "/v1/wiki/proposals/" + proposal.path("id").asString() + "/accept",
              "{\"base_version\":0}",
              "member");
      assertEquals(200, accepted.statusCode(), accepted.body());
      pageId = json(accepted).path("page_id").asString();
      var pending =
          json(
              request(context, "POST", "/v1/wiki/proposals", proposal(pageId, document), "member"));
      String sourceId =
          json(accepted)
              .path("content")
              .path("sections")
              .get(0)
              .path("sources")
              .get(0)
              .path("id")
              .asString();
      sourcePath = "/v1/wiki/pages/" + pageId + "/versions/1/sources/" + sourceId + "/content";
      var deleted =
          request(
              context,
              "DELETE",
              "/v1/wiki/pages/" + pageId + "?version=1&lifecycle_version=0",
              null,
              "other-member");
      assertEquals(200, deleted.statusCode(), deleted.body());
      assertEquals("deleted", json(deleted).path("state").asString());
      assertEquals(1, json(deleted).path("lifecycle_version").asInt());
      assertEquals(
          0,
          json(request(context, "GET", "/v1/wiki/pages?q=灯塔", null, "member"))
              .path("total")
              .asInt());
      assertEquals(
          1,
          json(request(context, "GET", "/v1/wiki/pages?state=deleted&q=灯塔", null, "member"))
              .path("total")
              .asInt());
      assertEquals(
          409,
          request(context, "POST", "/v1/wiki/proposals", proposal(pageId, document), "member")
              .statusCode());
      assertEquals(
          409,
          request(
                  context,
                  "POST",
                  "/v1/wiki/proposals/" + pending.path("id").asString() + "/accept",
                  "{\"base_version\":1}",
                  "member")
              .statusCode());
      assertEquals(original, request(context, "GET", sourcePath, null, "member").body());
      assertEquals(
          "deleted",
          json(request(context, "GET", "/v1/wiki/pages/" + pageId + "/versions/1", null, "member"))
              .path("state")
              .asString());
      assertEquals(
          409,
          request(
                  context,
                  "DELETE",
                  "/v1/wiki/pages/" + pageId + "?version=1&lifecycle_version=0",
                  null,
                  "member")
              .statusCode());
      assertEquals(
          400,
          request(context, "DELETE", "/v1/wiki/pages/" + pageId + "?version=1", null, "member")
              .statusCode());
      assertEquals(
          400, request(context, "GET", "/v1/wiki/pages?state=bogus", null, "member").statusCode());
      // The development authentication filter rejects a forged workspace before the controller.
      assertEquals(
          401,
          request(
                  context,
                  "DELETE",
                  "/v1/wiki/pages/" + pageId + "?version=1&lifecycle_version=1",
                  null,
                  "foreign")
              .statusCode());
    }
    try (var context = start()) {
      assertEquals(
          "deleted",
          json(request(context, "GET", "/v1/wiki/pages/" + pageId, null, "member"))
              .path("state")
              .asString());
      assertEquals(
          409,
          request(
                  context,
                  "POST",
                  "/v1/wiki/pages/" + pageId + "/restore",
                  "{\"version\":2,\"lifecycle_version\":1}",
                  "member")
              .statusCode());
      var restored =
          request(
              context,
              "POST",
              "/v1/wiki/pages/" + pageId + "/restore",
              "{\"version\":1,\"lifecycle_version\":1}",
              "member");
      assertEquals(200, restored.statusCode(), restored.body());
      assertEquals("active", json(restored).path("state").asString());
      assertEquals(2, json(restored).path("lifecycle_version").asInt());
      assertEquals(1, json(restored).path("version").asInt());
      assertEquals(
          1,
          json(request(context, "GET", "/v1/wiki/pages?q=灯塔", null, "member"))
              .path("total")
              .asInt());
      assertEquals(
          0,
          json(request(context, "GET", "/v1/wiki/pages?state=deleted", null, "member"))
              .path("total")
              .asInt());
      assertEquals(
          409,
          request(
                  context,
                  "DELETE",
                  "/v1/wiki/pages/" + pageId + "?version=1&lifecycle_version=0",
                  null,
                  "member")
              .statusCode());
      assertEquals(original, request(context, "GET", sourcePath, null, "member").body());
      assertEquals(
          201,
          request(context, "POST", "/v1/wiki/proposals", proposal(pageId, document), "member")
              .statusCode());
    }
  }

  private String proposal(String pageId, String document) {
    return "{"
        + (pageId == null ? "" : "\"page_id\":\"" + pageId + "\",")
        + "\"base_version\":"
        + (pageId == null ? 0 : 1)
        + ",\"title\":\"灯塔知识页\",\"kind\":\"topic\",\"document_ids\":[\""
        + document
        + "\"],\"generation_method\":\"extractive\"}";
  }

  private ConfigurableApplicationContext start() {
    var app = new SpringApplication(RagApplication.class);
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    app.setEnvironment(environment);
    var context =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=development_headers",
            "--rag.data-directory=" + directory);
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    return context;
  }

  private HttpResponse<String> request(
      ConfigurableApplicationContext context,
      String method,
      String path,
      String data,
      String principal)
      throws Exception {
    String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Workspace-Id", principal.equals("foreign") ? "other-org" : "org-main")
            .header("X-Principal-Id", principal);
    if (data != null) builder.header("Content-Type", "application/json");
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      return client.send(
          builder
              .method(
                  method,
                  data == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(data))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
