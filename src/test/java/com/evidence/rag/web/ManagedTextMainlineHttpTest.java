package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual local Spring, private persistence, parser/index JVMs and synthetic HTTP providers. */
class ManagedTextMainlineHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TEXT = "合成差旅政策。上海住宿标准为每晚650元。";
  @TempDir Path directory;

  @Test
  void setupTestActivateUploadIndexRecallAnswerSourceAndCompatibleRotation() throws Exception {
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      String sourceUrl;
      String documentId;
      try (var context = start(models, projection)) {
        String base = base(context);
        var state = request(http, base, "owner", "GET", "/v1/model-configuration", null, null, 200);
        assertEquals("unconfigured", state.path("state").asString());
        assertEquals(0, state.path("version").asInt());
        assertTrue(state.path("can_edit").booleanValue());
        assertEquals(9, state.size());
        var caps = request(http, base, "owner", "GET", "/v1/config", null, null, 200);
        assertEquals(6, caps.size());
        assertTrue(caps.path("capabilities").toString().contains("model_configuration"));
        assertFalse(caps.path("capabilities").toString().contains("text_index"));
        assertEquals(
            "text_configuration_required",
            request(
                    http,
                    base,
                    "owner",
                    "POST",
                    "/v1/answers",
                    json(Map.of("question", "上海住宿标准是多少？")),
                    "application/json",
                    503)
                .path("error_code")
                .asString());
        assertTrue(models.requests.isEmpty());
        assertTrue(projection.requests.isEmpty());
        var reader =
            request(http, base, "reader", "GET", "/v1/model-configuration", null, null, 200);
        assertFalse(reader.path("can_edit").booleanValue());
        request(
            http,
            base,
            "reader",
            "PUT",
            "/v1/model-configuration",
            json(configuration(0, "fixture-model", true)),
            "application/json",
            403);
        var saved =
            request(
                http,
                base,
                "owner",
                "PUT",
                "/v1/model-configuration",
                json(configuration(0, "fixture-model", true)),
                "application/json",
                200);
        assertEquals(1, saved.path("version").asInt());
        assertTrue(saved.path("active_version").isNull());
        assertFalse(saved.toString().contains("synthetic-managed-key"));
        assertTrue(models.requests.isEmpty());
        for (String role : List.of("embedding", "rerank", "generation", "projection")) {
          int count = models.requests.size() + projection.requests.size();
          var tested =
              request(
                  http,
                  base,
                  "owner",
                  "POST",
                  "/v1/model-configuration/test",
                  json(Map.of("version", 1, "role", role)),
                  "application/json",
                  200);
          assertEquals("passed", tested.path("status").asString(), tested.toString());
          assertEquals(count + 1, models.requests.size() + projection.requests.size());
        }
        int count = models.requests.size() + projection.requests.size();
        var applied =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/model-configuration/activate",
                json(Map.of("version", 1)),
                "application/json",
                200);
        assertEquals("active", applied.path("state").asString());
        assertEquals(count, models.requests.size() + projection.requests.size());
        caps = request(http, base, "owner", "GET", "/v1/config", null, null, 200);
        for (String capability :
            List.of("text_index", "indexings", "answers", "sources", "retrieval_test")) {
          assertTrue(
              caps.path("capabilities").toString().contains("\"" + capability + "\""),
              caps.toString());
        }
        byte[] content = TEXT.getBytes(StandardCharsets.UTF_8);
        var uploaded =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/documents?filename=synthetic-policy.txt",
                content,
                "application/octet-stream",
                202);
        documentId = uploaded.path("document_id").asString();
        await(http, base, "ingestions", uploaded.path("task_id").asString(), "parsed");
        var indexed =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/documents/" + documentId + "/index",
                null,
                null,
                202);
        await(http, base, "indexings", indexed.path("task_id").asString(), "indexed");
        assertFalse(projection.committedUpserts.isEmpty());
        var question = Map.of("question", "上海住宿标准是多少？", "document_ids", List.of(documentId));
        long generations =
            models.requests.stream().filter(r -> r.path().equals("/chat/completions")).count();
        var retrieved =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/retrieval-tests",
                json(question),
                "application/json",
                200);
        assertEquals("completed", retrieved.path("status").asString(), retrieved.toString());
        assertEquals(1, retrieved.path("configuration_version").asInt());
        assertEquals("rrf", retrieved.path("score_kind").asString());
        var match = retrieved.path("matches").get(0);
        assertEquals(documentId, match.path("document_id").asString());
        assertEquals(ModelValues.sha256(content), match.path("source_sha256").asString());
        assertTrue(match.path("text").asString().contains("650"));
        assertEquals(
            generations,
            models.requests.stream().filter(r -> r.path().equals("/chat/completions")).count());
        var empty =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/retrieval-tests",
                json(Map.of("question", "上海住宿标准是多少？", "document_ids", List.of())),
                "application/json",
                200);
        assertEquals("empty_scope", empty.path("reason").asString());
        var answer =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/answers",
                json(question),
                "application/json",
                200);
        assertEquals("answered", answer.path("status").asString(), answer.toString());
        sourceUrl = answer.path("citations").get(0).path("source_url").asString();
        request(http, base, "owner", "GET", sourceUrl, null, null, 200);
        request(
            http,
            base,
            "owner",
            "PUT",
            "/v1/model-configuration",
            json(configuration(1, "fixture-model", false, "fixture-v2")),
            "application/json",
            200);
        var rejected =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/model-configuration/activate",
                json(Map.of("version", 2)),
                "application/json",
                409);
        assertEquals("model_rebuild_required", rejected.path("error_code").asString());
        assertEquals(
            1,
            request(http, base, "owner", "GET", "/v1/model-configuration", null, null, 200)
                .path("active_version")
                .asInt());
        request(http, base, "owner", "GET", sourceUrl, null, null, 200);
        request(
            http,
            base,
            "owner",
            "PUT",
            "/v1/model-configuration",
            json(configuration(2, "fixture-model", true)),
            "application/json",
            200);
        request(
            http,
            base,
            "owner",
            "POST",
            "/v1/model-configuration/activate",
            json(Map.of("version", 3)),
            "application/json",
            200);
        request(http, base, "owner", "GET", sourceUrl, null, null, 200);
        assertFalse(
            context
                .getBean(SqliteAuthorityStore.class)
                .operationGate()
                .managedRoot()
                .resolve("private-model-settings")
                .toFile()
                .exists());
        assertTrue(
            context
                .getBean(SqliteAuthorityStore.class)
                .libraryPath()
                .getParent()
                .resolve("private-model-settings/text-models.json")
                .toFile()
                .isFile());
      }
      int count = models.requests.size() + projection.requests.size();
      try (var restored = start(models, projection)) {
        String base = base(restored);
        assertEquals(count, models.requests.size() + projection.requests.size());
        assertEquals(
            3,
            request(http, base, "owner", "GET", "/v1/model-configuration", null, null, 200)
                .path("active_version")
                .asInt());
        request(http, base, "owner", "GET", sourceUrl, null, null, 200);
        var result =
            request(
                http,
                base,
                "owner",
                "POST",
                "/v1/retrieval-tests",
                json(
                    Map.of(
                        "question",
                        "上海住宿标准是多少？",
                        "document_ids",
                        List.of(documentId),
                        "rerank",
                        false)),
                "application/json",
                200);
        assertTrue(result.path("matches").get(0).path("rerank_score").isNull());
      }
    }
  }

  private ConfigurableApplicationContext start(
      AnswerProtocolServer models, IndexingTestServer projection) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var defaults = new LinkedHashMap<String, Object>();
    defaults.put("RAG_MILVUS_ENDPOINT", projection.endpoint().toASCIIString());
    defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    defaults.put("RAG_MILVUS_COLLECTION", "java_index_process_fixture");
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(defaults);
    return application.run(
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--rag.environment=test",
        "--rag.workspace-id=org-main",
        "--rag.auth-mode=development_headers",
        "--rag.data-directory=" + directory,
        "--rag.ingestion.enabled=true",
        "--rag.indexing.enabled=true",
        "--rag.answers.enabled=true",
        "--rag.model-configuration.enabled=true",
        "--rag.model-configuration.administrators=owner",
        "--rag.model-configuration.provider-base-url=" + models.endpoint(),
        "--rag.model-configuration.allow-loopback-http=true",
        "--rag.model-configuration.deadline-ms=5000",
        "--rag.ingestion.parse-timeout-ms=15000",
        "--rag.indexing.timeout-ms=15000",
        "--rag.answers.timeout-ms=15000");
  }

  private static String base(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  private static byte[] json(Object value) {
    return JSON.writeValueAsBytes(value);
  }

  private static Map<String, Object> configuration(long base, String generation, boolean keys) {
    return configuration(base, generation, keys, "fixture-v1");
  }

  private static Map<String, Object> configuration(
      long base, String generation, boolean keys, String embeddingRevision) {
    var embedding = new LinkedHashMap<String, Object>();
    embedding.put("model", "fixture-model");
    embedding.put("dimensions", 2);
    embedding.put("revision", embeddingRevision);
    var rerank = new LinkedHashMap<String, Object>();
    rerank.put("model", "fixture-model");
    var generated = new LinkedHashMap<String, Object>();
    generated.put("model", generation);
    if (keys) {
      embedding.put("api_key", "synthetic-managed-key");
      rerank.put("api_key", "synthetic-managed-key");
      generated.put("api_key", "synthetic-managed-key");
    }
    return Map.of(
        "base_version", base, "embedding", embedding, "rerank", rerank, "generation", generated);
  }

  private static JsonNode request(
      HttpClient http,
      String base,
      String principal,
      String method,
      String path,
      byte[] content,
      String contentType,
      int status)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", principal)
            .header("Origin", base);
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    var response =
        http.send(
            builder
                .method(
                    method,
                    content == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static JsonNode await(
      HttpClient http, String base, String resource, String id, String state) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      var result =
          request(http, base, "owner", "GET", "/v1/" + resource + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(result.path("state").asString())) {
        assertEquals(state, result.path("state").asString(), result.toString());
        return result;
      }
      Thread.sleep(40);
    } while (System.nanoTime() < until);
    return fail("Synthetic managed " + resource + " did not finish");
  }
}
