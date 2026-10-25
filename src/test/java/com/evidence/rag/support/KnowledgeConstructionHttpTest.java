package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** New isolated application/data; only loopback provider and Milvus protocol fixtures. */
class KnowledgeConstructionHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void batchToReviewedKnowledgeAndChangedSourceToReviewedUpdateSurviveRestart() throws Exception {
    byte[] original = "合成指南：青榆设备，按蓝色启动键运行。".getBytes(StandardCharsets.UTF_8);
    byte[] unchanged = "合成维护记录：每月检查过滤网。".getBytes(StandardCharsets.UTF_8);
    byte[] updated = "合成指南：青榆设备，长按蓝色启动键三秒运行。".getBytes(StandardCharsets.UTF_8);
    String batchId;
    String pageId;
    String unchangedBody;
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint(), true);
        var http = HttpClient.newHttpClient()) {
      try (var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        WikiLocalIntegrationServer.activate(http, base);
        var folder =
            json(http, base, "POST", "/v1/management/folders", Map.of("name", "合成建设"), 201);
        var batch =
            json(
                http,
                base,
                "POST",
                "/v1/document-batches",
                Map.of(
                    "request_id",
                    "construction-http-batch",
                    "files",
                    List.of(
                        manifest("guide.txt", "manual/guide.txt", original),
                        manifest("care.txt", "manual/care.txt", unchanged)),
                    "target_folder_id",
                    folder.path("folder_id").asString(),
                    "preserve_paths",
                    true),
                202);
        batchId = batch.path("batch_id").asString();
        assertTrue(batch.path("preserve_paths").asBoolean());
        request(
            http,
            base,
            "POST",
            "/v1/document-batches/" + batchId + "/files/0",
            original,
            "application/octet-stream",
            202);
        request(
            http,
            base,
            "POST",
            "/v1/document-batches/" + batchId + "/files/1",
            unchanged,
            "application/octet-stream",
            202);
        batch =
            await(
                http,
                base,
                "/v1/document-batches/" + batchId,
                value -> value.path("counts").path("indexed").asInt() == 2);
        String documentId = batch.path("items").get(0).path("document_id").asString();
        String revisionId = batch.path("items").get(0).path("revision_id").asString();
        var sources =
            json(
                http,
                base,
                "GET",
                "/v1/document-batches/" + batchId + "/sources?folder=manual&limit=20",
                null,
                200);
        assertEquals(2, sources.path("total").asInt());
        var ids = new ArrayList<String>();
        var versions = new ArrayList<Map<String, String>>();
        for (var item : sources.path("items")) {
          ids.add(item.path("document_id").asString());
          versions.add(
              Map.of(
                  "document_id",
                  item.path("document_id").asString(),
                  "publication_id",
                  item.path("publication_id").asString()));
        }
        var proposal =
            json(
                http,
                base,
                "POST",
                "/v1/wiki/proposals",
                Map.of(
                    "title",
                    "合成设备知识",
                    "kind",
                    "topic",
                    "base_version",
                    0,
                    "document_ids",
                    ids,
                    "source_versions",
                    versions,
                    "generation_method",
                    "extractive"),
                201);
        assertEquals("pending", proposal.path("status").asString());
        var page =
            json(
                http,
                base,
                "POST",
                "/v1/wiki/proposals/" + proposal.path("id").asString() + "/accept",
                Map.of("base_version", 0),
                200);
        pageId = page.path("page_id").asString();
        assertEquals(2, page.path("content").path("sections").size());
        unchangedBody = new String(unchanged, StandardCharsets.UTF_8);
        var noChanges =
            json(http, base, "GET", "/v1/wiki/pages/" + pageId + "/source-changes", null, 200);
        assertFalse(noChanges.path("can_refresh").asBoolean());

        request(
            http,
            base,
            "POST",
            "/v1/documents/"
                + documentId
                + "/replacement?filename=guide.txt&base_revision_id="
                + revisionId,
            updated,
            "application/octet-stream",
            202);
        await(
            http,
            base,
            "/v1/documents/" + documentId + "/replacement",
            value -> "published".equals(value.path("state").asString()));
        var changes =
            json(http, base, "GET", "/v1/wiki/pages/" + pageId + "/source-changes", null, 200);
        assertTrue(changes.path("can_refresh").asBoolean(), changes.toString());
        assertEquals(
            1,
            json(http, base, "GET", "/v1/wiki/maintenance?kind=stale_knowledge", null, 200)
                .path("total")
                .asInt());
        // The batch selection was bound to its original publication, not silently rebound.
        assertEquals(
            1,
            json(http, base, "GET", "/v1/document-batches/" + batchId + "/sources", null, 200)
                .path("total")
                .asInt());
        json(
            http,
            base,
            "POST",
            "/v1/wiki/proposals",
            Map.of(
                "title",
                "过期选择",
                "kind",
                "topic",
                "base_version",
                0,
                "document_ids",
                ids,
                "source_versions",
                versions,
                "generation_method",
                "extractive"),
            409);
        versions.clear();
        for (var source : changes.path("sources")) {
          versions.add(
              Map.of(
                  "document_id",
                  source.path("document_id").asString(),
                  "publication_id",
                  source.path("after").path("publication_id").asString()));
        }
        var refresh =
            json(
                http,
                base,
                "POST",
                "/v1/wiki/pages/" + pageId + "/refresh-proposal",
                Map.of(
                    "base_version",
                    1,
                    "generation_method",
                    "extractive",
                    "source_versions",
                    versions),
                201);
        assertEquals("pending", refresh.path("status").asString());
        assertEquals(
            1,
            json(http, base, "GET", "/v1/wiki/pages/" + pageId, null, 200).path("version").asInt());
        var second =
            json(
                http,
                base,
                "POST",
                "/v1/wiki/proposals/" + refresh.path("id").asString() + "/accept",
                Map.of("base_version", 1),
                200);
        assertEquals(2, second.path("version").asInt());
        assertTrue(
            second
                .path("content")
                .path("sections")
                .valueStream()
                .anyMatch(section -> unchangedBody.equals(section.path("body").asString())));
        assertTrue(
            second
                .path("content")
                .path("sections")
                .valueStream()
                .anyMatch(section -> section.path("body").asString().contains("三秒")));
        assertEquals(
            0,
            json(http, base, "GET", "/v1/wiki/maintenance?kind=stale_knowledge", null, 200)
                .path("total")
                .asInt());
      }
      int calls = models.requests.size();
      try (var app = WikiLocalIntegrationServer.start(directory, models, projection, 0)) {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        var batch = json(http, base, "GET", "/v1/document-batches/" + batchId, null, 200);
        assertTrue(batch.path("preserve_paths").asBoolean());
        assertEquals("合成建设", batch.path("target_folder_name").asString());
        assertEquals(
            2,
            json(http, base, "GET", "/v1/wiki/pages/" + pageId, null, 200).path("version").asInt());
        assertEquals(
            1,
            json(http, base, "GET", "/v1/wiki/pages/" + pageId + "/versions/1", null, 200)
                .path("version")
                .asInt());
        assertEquals(calls, models.requests.size(), "Readback must not call models");
      }
    }
  }

  private static Map<String, Object> manifest(String filename, String path, byte[] bytes) {
    return Map.of(
        "filename",
        filename,
        "relative_path",
        path,
        "size_bytes",
        bytes.length,
        "source_sha256",
        ModelValues.sha256(bytes));
  }

  private static JsonNode await(HttpClient http, String base, String path, Predicate<JsonNode> done)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    JsonNode last;
    do {
      last = json(http, base, "GET", path, null, 200);
      if (done.test(last)) return last;
      if (last.path("counts").path("failed").asInt() > 0
          || "failed".equals(last.path("state").asString())) fail(last.toString());
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Workflow did not complete: " + last);
  }

  private static JsonNode json(
      HttpClient http, String base, String method, String path, Object body, int expected)
      throws Exception {
    return request(
        http,
        base,
        method,
        path,
        body == null ? null : JSON.writeValueAsBytes(body),
        body == null ? null : "application/json",
        expected);
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
