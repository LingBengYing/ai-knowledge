package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual application upload/parser/worker/SQLite with explicit loopback model and vector adapters.
 */
class WikiWorkflowHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TEXT = "青榆灯塔项目。计划启动日期：2026年11月18日。计划预算：人民币48600元。";
  @TempDir Path directory;

  @Test
  void uploadedOriginalCompilesAnswersAndPersistsAcrossApplicationRestart() throws Exception {
    assertNotNull(directory);
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint());
        var http = HttpClient.newHttpClient()) {
      String pageId;
      String draftId;
      String sourcePath;
      String answerSourcePath;
      try (var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
        assertEquals(directory.toString(), app.getEnvironment().getProperty("rag.data-directory"));
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        WikiLocalIntegrationServer.activate(http, base);
        var upload =
            request(
                http,
                base,
                "POST",
                "/v1/documents?filename=wiki-live.txt",
                TEXT.getBytes(StandardCharsets.UTF_8),
                "application/octet-stream",
                202);
        await(http, base, "/v1/ingestions/" + upload.path("task_id").asString(), "parsed");
        String documentId = upload.path("document_id").asString();
        var indexing =
            request(http, base, "POST", "/v1/documents/" + documentId + "/index", null, null, 202);
        await(http, base, "/v1/indexings/" + indexing.path("task_id").asString(), "indexed");
        assertFalse(projection.committedUpserts.isEmpty());
        var files =
            request(http, base, "GET", "/v1/wiki/catalog?q=%E7%81%AF%E5%A1%94", null, null, 200);
        assertEquals(1, files.path("total").asInt());
        assertTrue(files.path("items").get(0).path("answerable").asBoolean());
        var proposal =
            request(
                http,
                base,
                "POST",
                "/v1/wiki/proposals",
                JSON.writeValueAsBytes(
                    Map.of(
                        "title",
                        "灯塔项目",
                        "kind",
                        "topic",
                        "base_version",
                        0,
                        "document_ids",
                        List.of(documentId),
                        "generation_method",
                        "model")),
                "application/json",
                201);
        assertEquals("pending", proposal.path("status").asString());
        var page =
            request(
                http,
                base,
                "POST",
                "/v1/wiki/proposals/" + proposal.path("id").asString() + "/accept",
                JSON.writeValueAsBytes(Map.of("base_version", 0)),
                "application/json",
                200);
        pageId = page.path("page_id").asString();
        assertEquals(TEXT, page.path("content").path("sections").get(0).path("body").asString());
        String sourceId =
            page.path("content")
                .path("sections")
                .get(0)
                .path("sources")
                .get(0)
                .path("id")
                .asString();
        sourcePath = "/v1/wiki/pages/" + pageId + "/versions/1/sources/" + sourceId;
        assertEquals(
            TEXT, request(http, base, "GET", sourcePath, null, null, 200).path("text").asString());
        var answer =
            request(
                http,
                base,
                "POST",
                "/v1/knowledge-answers",
                JSON.writeValueAsBytes(Map.of("question", "灯塔项目预算是多少？")),
                "application/json",
                200);
        assertEquals("answered", answer.path("status").asString(), answer.toString());
        assertTrue(answer.path("answer").asString().contains("48600"));
        answerSourcePath = answer.path("citations").get(0).path("source_url").asString();
        request(http, base, "GET", answerSourcePath, null, null, 200);
        var draft =
            request(
                http,
                base,
                "POST",
                "/v1/wiki/drafts",
                JSON.writeValueAsBytes(
                    Map.of("title", "待确认的回答草稿", "body", answer.path("answer").asString())),
                "application/json",
                201);
        draftId = draft.path("id").asString();
        assertEquals(1, draft.path("version").asInt());
      }
      int providerCount = models.requests.size();
      try (var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        assertEquals(
            pageId,
            request(http, base, "GET", "/v1/wiki/pages/" + pageId, null, null, 200)
                .path("page_id")
                .asString());
        assertEquals(
            draftId,
            request(http, base, "GET", "/v1/wiki/drafts/" + draftId, null, null, 200)
                .path("id")
                .asString());
        assertEquals(
            TEXT, request(http, base, "GET", sourcePath, null, null, 200).path("text").asString());
        request(http, base, "GET", answerSourcePath, null, null, 200);
        assertEquals(
            providerCount,
            models.requests.size(),
            "Stored pages, drafts and sources must not regenerate on restart");
      }
    }
  }

  private static void await(HttpClient http, String base, String path, String expected)
      throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      var state = request(http, base, "GET", path, null, null, 200);
      if (!List.of("queued", "processing").contains(state.path("state").asString())) {
        assertEquals(expected, state.path("state").asString(), state.toString());
        return;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < until);
    fail("Local task did not finish");
  }

  private static JsonNode request(
      HttpClient http,
      String base,
      String method,
      String path,
      byte[] body,
      String type,
      int expected)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Origin", base)
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "owner");
    if (type != null) builder.header("Content-Type", type);
    var response =
        http.send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
