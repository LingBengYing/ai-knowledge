package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Real HTTP, authority publication and restart; no external model or vector requests. */
class WikiWorkspaceHttpTest {
  @TempDir static Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void catalogFindsWholePublishedTextAndDraftsArePersistentUnverifiedEdits() throws Exception {
    assertNotNull(directory);
    String documentId;
    try (var corpus = new PublishedCorpusFixture(directory)) {
      documentId =
          corpus.publish(new Actor("org-main", "owner"), "目录检索唯一词：北斗；这是完整原文。").documentId();
    }
    String id;
    try (var context = start()) {
      var found = request(context, "GET", "/v1/wiki/catalog?q=北斗&limit=1", null, "member");
      assertEquals(200, found.statusCode(), found.body());
      assertEquals(1, body(found).path("total").asInt());
      var item = body(found).path("items").get(0);
      assertEquals(documentId, item.path("document_id").asString(), found.body());
      assertTrue(item.has("display_name"), found.body());
      assertEquals("text/plain", item.path("media_type").asString());
      assertFalse(item.path("source_revision_id").asString().isBlank());
      assertEquals(64, item.path("source_sha256").asString().length());
      assertEquals(1, item.path("match_count").asInt());
      for (String forbidden :
          new String[] {
            "documentId",
            "displayName",
            "mediaType",
            "sourceRevisionId",
            "sourceSha256",
            "matchCount"
          }) {
        assertFalse(item.has(forbidden), found.body());
      }
      assertTrue(item.path("answerable").asBoolean());
      assertTrue(item.path("excerpt").asString().contains("北斗"));
      var created =
          request(
              context,
              "POST",
              "/v1/wiki/drafts",
              "{\"title\":\"未核验笔记\",\"body\":\"正文\\n第二行\"}",
              "member");
      assertEquals(201, created.statusCode(), created.body());
      id = body(created).path("id").asString();
      assertEquals(1, body(created).path("version").asInt());
      long createdAt = body(created).path("created_at").asLong();
      assertTrue(createdAt > 0, created.body());
      assertEquals(createdAt, body(created).path("updated_at").asLong());
      assertFalse(body(created).has("createdAt"));
      assertFalse(body(created).has("updatedAt"));
      var saved =
          request(
              context,
              "PUT",
              "/v1/wiki/drafts/" + id,
              "{\"version\":1,\"title\":\"修订笔记\",\"body\":\"新正文\"}",
              "other-member");
      assertEquals(200, saved.statusCode(), saved.body());
      assertEquals(2, body(saved).path("version").asInt());
      assertEquals(createdAt, body(saved).path("created_at").asLong());
      assertTrue(body(saved).path("updated_at").asLong() >= createdAt);
      var drafts = request(context, "GET", "/v1/wiki/drafts", null, "member");
      assertEquals(200, drafts.statusCode(), drafts.body());
      assertEquals(createdAt, body(drafts).path("items").get(0).path("created_at").asLong());
      assertFalse(body(drafts).path("items").get(0).has("createdAt"));
      assertEquals(
          409,
          request(context, "DELETE", "/v1/wiki/drafts/" + id + "?version=1", null, "member")
              .statusCode());
      assertEquals(
          400,
          request(
                  context,
                  "POST",
                  "/v1/wiki/drafts",
                  "{\"title\":\"伪来源\",\"body\":\"正文\",\"sources\":[]}",
                  "member")
              .statusCode());
    }
    try (var context = start()) {
      var saved = request(context, "GET", "/v1/wiki/drafts/" + id, null, "member");
      assertEquals(200, saved.statusCode());
      assertEquals("新正文", body(saved).path("body").asString());
      assertTrue(body(saved).path("created_at").asLong() > 0, saved.body());
      assertTrue(body(saved).path("updated_at").asLong() > 0, saved.body());
      assertEquals(
          204,
          request(context, "DELETE", "/v1/wiki/drafts/" + id + "?version=2", null, "member")
              .statusCode());
      assertEquals(
          404, request(context, "GET", "/v1/wiki/drafts/" + id, null, "member").statusCode());
    }
  }

  @Test
  void proposedOriginalsBecomeVersionedKnowledgeOnlyAfterReviewAndSurviveRestart()
      throws Exception {
    assertNotNull(directory);
    String document;
    try (var corpus = new PublishedCorpusFixture(directory)) {
      document =
          corpus.publish(new Actor("org-main", "owner"), "灯塔项目：按下启动按钮。\n确认绿灯亮起。").documentId();
    }
    String pageId;
    String sourcePath;
    try (var context = start()) {
      var empty = request(context, "GET", "/v1/wiki/pages", null, "member");
      assertEquals(200, empty.statusCode());
      assertEquals(0, body(empty).path("total").asInt());
      var proposed =
          request(
              context,
              "POST",
              "/v1/wiki/proposals",
              """
          {"base_version":0,"title":"灯塔使用方法","kind":"procedure",
           "document_ids":["%s"],"generation_method":"extractive"}
          """
                  .formatted(document),
              "member");
      assertEquals(201, proposed.statusCode(), proposed.body());
      var proposal = body(proposed);
      String proposalId = proposal.path("id").asString();
      pageId = proposal.path("page_id").asString();
      assertEquals("pending", proposal.path("status").asString());
      assertEquals("extractive", proposal.path("generation_method").asString());
      assertTrue(
          proposal.path("after").path("sections").get(0).path("body").asString().contains("确认绿灯"));
      assertEquals(
          0, body(request(context, "GET", "/v1/wiki/pages", null, "member")).path("total").asInt());
      String sourceId =
          proposal
              .path("after")
              .path("sections")
              .get(0)
              .path("sources")
              .get(0)
              .path("id")
              .asString();
      var reviewSource =
          request(
              context,
              "GET",
              "/v1/wiki/proposals/" + proposalId + "/sources/" + sourceId,
              null,
              "member");
      assertEquals(200, reviewSource.statusCode());
      assertEquals("page", body(reviewSource).path("locator").path("type").asString());
      assertEquals(1, body(reviewSource).path("locator").path("page").asInt());
      var accepted =
          request(
              context,
              "POST",
              "/v1/wiki/proposals/" + proposalId + "/accept",
              "{\"base_version\":0}",
              "other-member");
      assertEquals(200, accepted.statusCode(), accepted.body());
      assertEquals(1, body(accepted).path("version").asInt());
      assertEquals("current", body(accepted).path("source_state").asString());
      sourcePath = "/v1/wiki/pages/" + pageId + "/versions/1/sources/" + sourceId;
      assertEquals(200, request(context, "GET", sourcePath, null, "member").statusCode());
      assertEquals(
          409,
          request(
                  context,
                  "POST",
                  "/v1/wiki/proposals/" + proposalId + "/accept",
                  "{\"base_version\":0}",
                  "member")
              .statusCode());
      assertFalse(accepted.body().contains("projectionGenerationId"));
      assertFalse(accepted.body().contains("embeddingIdentity"));
      assertFalse(accepted.body().contains("/private/"));
      assertEquals(
          400,
          request(
                  context,
                  "POST",
                  "/v1/wiki/proposals",
                  "{\"base_version\":0,\"base_version\":1}",
                  "member")
              .statusCode());
      assertEquals(
          400,
          request(context, "GET", "/v1/wiki/pages?limit=2&limit=3", null, "member").statusCode());
      // Development-header authentication already reports missing identity as 422 (not JWT 401).
      assertEquals(422, request(context, "GET", "/v1/wiki/pages", null, null).statusCode());
    }
    try (var restarted = start()) {
      var page = request(restarted, "GET", "/v1/wiki/pages/" + pageId, null, "member");
      assertEquals(200, page.statusCode());
      assertEquals(1, body(page).path("version").asInt());
      var source = request(restarted, "GET", sourcePath, null, "member");
      assertEquals(200, source.statusCode());
      var content =
          request(restarted, "GET", body(source).path("content_url").asString(), null, "member");
      assertEquals(200, content.statusCode());
      assertTrue(content.body().contains("确认绿灯亮起"));
      assertEquals("private, no-store", source.headers().firstValue("Cache-Control").orElseThrow());
    }
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
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
    if (principal != null) {
      builder.header("X-Workspace-Id", "org-main").header("X-Principal-Id", principal);
    }
    if (data != null) {
      builder.header("Content-Type", "application/json");
    }
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

  private JsonNode body(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
