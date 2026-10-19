package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.RagApplication;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

class DocumentBatchHttpTest {
  @TempDir Path directory;

  @Test
  void actualHttpManifestUploadAutoIndexAndReadbackUseOnePersistentBatch() throws Exception {
    var json = JsonMapper.builder().build();
    var client = HttpClient.newHttpClient();
    try (var upstream = new IndexingTestServer()) {
      var defaults = new LinkedHashMap<String, Object>();
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        defaults.put("RAG_" + kind + "_BASE_URL", upstream.endpoint().toString());
        defaults.put("RAG_" + kind + "_MODEL", "fixture-model");
        defaults.put("RAG_" + kind + "_API_KEY", "synthetic-model-credential");
      }
      defaults.put("RAG_EMBEDDING_DIMENSIONS", "2");
      defaults.put("RAG_EMBEDDING_REVISION", "fixture-v1");
      defaults.put("RAG_MILVUS_ENDPOINT", upstream.endpoint().toString());
      defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
      defaults.put("RAG_MILVUS_COLLECTION", upstream.settings().projection().collection());
      defaults.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
      var app = new SpringApplication(RagApplication.class);
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      app.setEnvironment(environment);
      app.setDefaultProperties(defaults);
      try (var context =
          app.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + directory,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.indexing.timeout-ms=20000")) {
        String base =
            "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        byte[] raw = "Synthetic HTTP batch source".getBytes(StandardCharsets.UTF_8);
        byte[] command =
            json.writeValueAsBytes(
                Map.of(
                    "request_id",
                    "http-batch",
                    "embedding_request_limit",
                    2,
                    "include_duplicates",
                    true,
                    "files",
                    List.of(
                        Map.of(
                            "filename",
                            "one.txt",
                            "size_bytes",
                            raw.length,
                            "source_sha256",
                            ModelValues.sha256(raw)),
                        Map.of(
                            "filename",
                            "two.txt",
                            "size_bytes",
                            raw.length,
                            "source_sha256",
                            ModelValues.sha256(raw)))));
        var admitted =
            send(client, base, "POST", "/v1/document-batches", command, "application/json");
        assertEquals(202, admitted.statusCode(), admitted.body());
        String id = json.readTree(admitted.body()).path("batch_id").asString();
        for (int i = 0; i < 2; i++)
          assertEquals(
              202,
              send(
                      client,
                      base,
                      "POST",
                      "/v1/document-batches/" + id + "/files/" + i,
                      raw,
                      "application/octet-stream")
                  .statusCode());
        var duplicate =
            send(
                client,
                base,
                "POST",
                "/v1/document-batches/" + id + "/files/0",
                raw,
                "application/octet-stream");
        assertEquals(202, duplicate.statusCode());
        long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        String status = "";
        do {
          var response =
              send(
                  client,
                  base,
                  "GET",
                  "/v1/document-batches/" + id + "?offset=0&limit=100",
                  null,
                  null);
          assertEquals(200, response.statusCode(), response.body());
          var batch = json.readTree(response.body());
          status = batch.path("state").asString();
          assertNotEquals("halted", status, response.body());
          if (status.equals("completed")) {
            assertEquals(2, batch.path("observed_requests").asInt());
            assertFalse(batch.path("usage_uncertain").asBoolean());
            for (var file : batch.path("items")) {
              var original =
                  send(
                      client,
                      base,
                      "GET",
                      "/v1/documents/" + file.path("document_id").asString() + "/original",
                      null,
                      null);
              assertEquals(200, original.statusCode());
              assertEquals(
                  ModelValues.sha256(raw),
                  json.readTree(original.body()).path("source_sha256").asString());
            }
            break;
          }
          Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertEquals("completed", status);
        assertEquals(
            2, upstream.requests.stream().filter(r -> r.path().equals("/embeddings")).count());
        assertEquals(
            0,
            upstream.requests.stream()
                .filter(r -> r.path().contains("chat/completions") || r.path().contains("rerank"))
                .count());
      }
    }
  }

  @Test
  void actualHttpBulkActionsFollowTheBatchState() throws Exception {
    var json = JsonMapper.builder().build();
    var client = HttpClient.newHttpClient();
    try (var upstream = new IndexingTestServer()) {
      var defaults = new LinkedHashMap<String, Object>();
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        defaults.put("RAG_" + kind + "_BASE_URL", upstream.endpoint().toString());
        defaults.put("RAG_" + kind + "_MODEL", "fixture-model");
        defaults.put("RAG_" + kind + "_API_KEY", "synthetic-model-credential");
      }
      defaults.put("RAG_EMBEDDING_DIMENSIONS", "2");
      defaults.put("RAG_EMBEDDING_REVISION", "fixture-v1");
      defaults.put("RAG_MILVUS_ENDPOINT", upstream.endpoint().toString());
      defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
      defaults.put("RAG_MILVUS_COLLECTION", upstream.settings().projection().collection());
      defaults.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
      var app = new SpringApplication(RagApplication.class);
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      app.setEnvironment(environment);
      app.setDefaultProperties(defaults);
      try (var context =
          app.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + directory,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true")) {
        String base =
            "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        byte[] raw = "Synthetic HTTP bulk source".getBytes(StandardCharsets.UTF_8);
        byte[] command =
            json.writeValueAsBytes(
                Map.of(
                    "request_id",
                    "http-bulk",
                    "files",
                    List.of(
                        Map.of(
                            "filename",
                            "one.txt",
                            "size_bytes",
                            raw.length,
                            "source_sha256",
                            ModelValues.sha256(raw)))));
        var admitted =
            send(client, base, "POST", "/v1/document-batches", command, "application/json");
        assertEquals(202, admitted.statusCode(), admitted.body());
        var created = json.readTree(admitted.body());
        String id = created.path("batch_id").asString();
        assertEquals("awaiting_upload", created.path("state").asString());
        assertEquals(0, created.path("retryable_files").asInt());
        assertEquals("[\"cancel\"]", created.path("available_actions").toString());
        // The import history lists it; a state-filtered page and per-file fields are served.
        var history =
            send(client, base, "GET", "/v1/document-batches?offset=0&limit=5", null, null);
        assertEquals(200, history.statusCode(), history.body());
        var listed = json.readTree(history.body());
        assertEquals(1, listed.path("total").asInt());
        assertEquals(id, listed.path("items").path(0).path("batch_id").asString());
        assertEquals("awaiting_upload", listed.path("items").path(0).path("state").asString());
        assertFalse(listed.path("items").path(0).path("created_at").asString().isEmpty());
        var filtered =
            send(
                client,
                base,
                "GET",
                "/v1/document-batches/" + id + "?state=awaiting_upload,failed&offset=0&limit=10",
                null,
                null);
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertEquals(1, json.readTree(filtered.body()).path("matched").asInt());
        assertTrue(json.readTree(filtered.body()).path("items").path(0).has("relative_path"));
        for (String rejected :
            List.of(
                "/v1/document-batches?offset=0&limit=101",
                "/v1/document-batches?state=failed",
                "/v1/document-batches?offset=0&offset=1",
                "/v1/document-batches/" + id + "?state=bogus",
                "/v1/document-batches/" + id + "?state=",
                "/v1/document-batches/" + id + "?state=failed,failed"))
          assertTrue(
              send(client, base, "GET", rejected, null, null).statusCode() / 100 == 4, rejected);
        String path = "/v1/document-batches/" + id;

        var retried = send(client, base, "POST", path + "/retry-failed", null, null);
        assertEquals(200, retried.statusCode(), retried.body());
        var retry = json.readTree(retried.body());
        assertEquals("retry_failed", retry.path("action").asString());
        assertEquals(0, retry.path("affected").asInt());
        assertEquals(id, retry.path("batch").path("batch_id").asString());

        assertEquals(405, send(client, base, "GET", path + "/cancel", null, null).statusCode());
        assertEquals(
            422, send(client, base, "POST", path + "/cancel?force=1", null, null).statusCode());
        assertEquals(
            404,
            send(client, base, "POST", "/v1/document-batches/missing/cancel", null, null)
                .statusCode());
        var resumeEarly = send(client, base, "POST", path + "/resume", null, null);
        assertEquals(409, resumeEarly.statusCode(), resumeEarly.body());
        assertTrue(resumeEarly.body().contains("batch_action_conflict"));

        var cancelled = send(client, base, "POST", path + "/cancel", null, null);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        var cancel = json.readTree(cancelled.body()).path("batch");
        assertEquals("cancelled", cancel.path("state").asString());
        assertEquals("batch_cancelled", cancel.path("halted_reason").asString());
        assertEquals("[\"resume\"]", cancel.path("available_actions").toString());
        assertEquals(409, send(client, base, "POST", path + "/cancel", null, null).statusCode());
        assertEquals(
            409, send(client, base, "POST", path + "/retry-failed", null, null).statusCode());
        assertEquals(
            409,
            send(client, base, "POST", path + "/files/0", raw, "application/octet-stream")
                .statusCode());

        byte[] tooLow = json.writeValueAsBytes(Map.of("embedding_request_limit", 0));
        assertEquals(
            422,
            send(client, base, "POST", path + "/resume", tooLow, "application/json").statusCode());
        byte[] limit = json.writeValueAsBytes(Map.of("embedding_request_limit", 50));
        var resumed = send(client, base, "POST", path + "/resume", limit, "application/json");
        assertEquals(200, resumed.statusCode(), resumed.body());
        var resume = json.readTree(resumed.body()).path("batch");
        assertEquals("awaiting_upload", resume.path("state").asString());
        assertEquals(50, resume.path("embedding_request_limit").asInt());
        assertTrue(resume.path("halted_reason").isNull());
        assertEquals(
            202,
            send(client, base, "POST", path + "/files/0", raw, "application/octet-stream")
                .statusCode());
        assertFalse(resume.path("open").asBoolean());
        assertTrue(resume.path("expected_total").isNull());
        assertEquals(
            409, send(client, base, "POST", path + "/seal", null, null).statusCode(), "one-shot");

        // v42: an open batch grows by appended chunks, uploads in between, then is sealed.
        byte[] open =
            json.writeValueAsBytes(
                Map.of("request_id", "http-open", "open", true, "expected_total", 2));
        var opened = send(client, base, "POST", "/v1/document-batches", open, "application/json");
        assertEquals(202, opened.statusCode(), opened.body());
        var openBatch = json.readTree(opened.body());
        String openPath = "/v1/document-batches/" + openBatch.path("batch_id").asString();
        assertTrue(openBatch.path("open").asBoolean());
        assertEquals(2, openBatch.path("expected_total").asInt());
        assertEquals("[\"cancel\",\"seal\"]", openBatch.path("available_actions").toString());
        byte[] other = "Synthetic HTTP open source".getBytes(StandardCharsets.UTF_8);
        byte[] chunk =
            json.writeValueAsBytes(
                Map.of(
                    "files",
                    List.of(
                        Map.of(
                            "ordinal",
                            0,
                            "filename",
                            "three.txt",
                            "size_bytes",
                            other.length,
                            "source_sha256",
                            ModelValues.sha256(other),
                            "relative_path",
                            "folder/three.txt"))));
        var appended = send(client, base, "POST", openPath + "/files", chunk, "application/json");
        assertEquals(200, appended.statusCode(), appended.body());
        var appendResult = json.readTree(appended.body());
        assertEquals(1, appendResult.path("registered").asInt());
        assertEquals(
            "awaiting_upload", appendResult.path("items").path(0).path("state").asString());
        assertEquals(
            200,
            send(client, base, "POST", openPath + "/files", chunk, "application/json").statusCode(),
            "a replay succeeds");
        byte[] conflicting =
            json.writeValueAsBytes(
                Map.of(
                    "files",
                    List.of(
                        Map.of(
                            "ordinal",
                            0,
                            "filename",
                            "four.txt",
                            "size_bytes",
                            other.length,
                            "source_sha256",
                            ModelValues.sha256(other)))));
        var conflict =
            send(client, base, "POST", openPath + "/files", conflicting, "application/json");
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertTrue(conflict.body().contains("batch_upload_conflict"));
        assertEquals(
            422,
            send(client, base, "POST", openPath + "/files?x=1", chunk, "application/json")
                .statusCode());
        assertEquals(
            202,
            send(client, base, "POST", openPath + "/files/0", other, "application/octet-stream")
                .statusCode());
        var sealed = send(client, base, "POST", openPath + "/seal", null, null);
        assertEquals(200, sealed.statusCode(), sealed.body());
        var seal = json.readTree(sealed.body());
        assertEquals("seal", seal.path("action").asString());
        assertFalse(seal.path("batch").path("open").asBoolean());
        assertEquals(1, seal.path("batch").path("total").asInt());
        var sealedAppend =
            send(
                client,
                base,
                "POST",
                openPath + "/files",
                json.writeValueAsBytes(
                    Map.of(
                        "files",
                        List.of(
                            Map.of(
                                "ordinal",
                                1,
                                "filename",
                                "five.txt",
                                "size_bytes",
                                1,
                                "source_sha256",
                                "e".repeat(64))))),
                "application/json");
        assertEquals(409, sealedAppend.statusCode(), sealedAppend.body());
        assertTrue(sealedAppend.body().contains("batch_sealed"));
      }
    }
  }

  static HttpResponse<String> send(
      HttpClient client, String base, String method, String path, byte[] bytes, String type)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "owner")
            .header("Origin", base);
    if (type != null) request.header("Content-Type", type);
    return client.send(
        request
            .method(
                method,
                bytes == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
