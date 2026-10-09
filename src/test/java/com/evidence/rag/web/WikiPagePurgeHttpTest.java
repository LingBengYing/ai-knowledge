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

class WikiPagePurgeHttpTest {
  @TempDir static Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void permanentDeletionRequiresDeletedCurrentPageAndPreservesOriginalAfterRestart()
      throws Exception {
    assertNotNull(directory);
    String document;
    String originalPath;
    try (var corpus = new PublishedCorpusFixture(directory)) {
      var published = corpus.publish(new Actor("org-main", "owner"), "合成资料，原件必须保留。");
      document = published.documentId();
      originalPath =
          "/v1/documents/" + document + "/revisions/" + published.revisionId() + "/content";
    }
    String pageId;
    String initialProposal;
    String pendingProposal;
    String original;
    try (var context = start()) {
      var proposal = json(request(context, "POST", "/v1/wiki/proposals", proposal(null, document)));
      initialProposal = proposal.path("id").asString();
      var page =
          json(
              request(
                  context,
                  "POST",
                  "/v1/wiki/proposals/" + initialProposal + "/accept",
                  "{\"base_version\":0}"));
      pageId = page.path("page_id").asString();
      pendingProposal =
          json(request(context, "POST", "/v1/wiki/proposals", proposal(pageId, document)))
              .path("id")
              .asString();
      var originalResponse = request(context, "GET", originalPath, null);
      assertEquals(200, originalResponse.statusCode(), originalResponse.body());
      original = originalResponse.body();
      String purge = "/v1/wiki/pages/" + pageId + "/purge";
      assertEquals(
          409,
          request(context, "DELETE", purge + "?version=1&lifecycle_version=0", null).statusCode());
      assertEquals(400, request(context, "DELETE", purge + "?version=1", null).statusCode());
      assertEquals(
          200,
          request(
                  context,
                  "DELETE",
                  "/v1/wiki/pages/" + pageId + "?version=1&lifecycle_version=0",
                  null)
              .statusCode());
      assertEquals(
          409,
          request(context, "DELETE", purge + "?version=2&lifecycle_version=1", null).statusCode());
      assertEquals(
          409,
          request(context, "DELETE", purge + "?version=1&lifecycle_version=0", null).statusCode());
      var purged = request(context, "DELETE", purge + "?version=1&lifecycle_version=1", null);
      assertEquals(200, purged.statusCode(), purged.body());
      assertEquals("{\"page_id\":\"" + pageId + "\",\"state\":\"purged\"}", purged.body());
      assertEquals(
          404,
          request(context, "DELETE", purge + "?version=1&lifecycle_version=1", null).statusCode());
    }
    try (var context = start()) {
      for (String path :
          new String[] {
            "/v1/wiki/pages/" + pageId,
            "/v1/wiki/pages/" + pageId + "/versions/1",
            "/v1/wiki/proposals/" + initialProposal,
            "/v1/wiki/proposals/" + pendingProposal
          }) {
        assertEquals(404, request(context, "GET", path, null).statusCode(), path);
      }
      assertEquals(
          404,
          request(
                  context,
                  "POST",
                  "/v1/wiki/pages/" + pageId + "/restore",
                  "{\"version\":1,\"lifecycle_version\":1}")
              .statusCode());
      assertEquals(
          0,
          json(request(context, "GET", "/v1/wiki/pages?state=deleted", null))
              .path("total")
              .asInt());
      assertEquals(0, json(request(context, "GET", "/v1/wiki/pages", null)).path("total").asInt());
      var retained = request(context, "GET", originalPath, null);
      assertEquals(200, retained.statusCode(), retained.body());
      assertEquals(original, retained.body());
    }
  }

  private String proposal(String pageId, String document) {
    return "{"
        + (pageId == null ? "" : "\"page_id\":\"" + pageId + "\",")
        + "\"base_version\":"
        + (pageId == null ? 0 : 1)
        + ",\"title\":\"合成待清理知识页\",\"kind\":\"topic\",\"document_ids\":[\""
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
      ConfigurableApplicationContext context, String method, String path, String body)
      throws Exception {
    String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "member");
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
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
  }

  private JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
