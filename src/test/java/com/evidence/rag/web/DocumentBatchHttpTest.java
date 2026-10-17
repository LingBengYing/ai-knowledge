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
