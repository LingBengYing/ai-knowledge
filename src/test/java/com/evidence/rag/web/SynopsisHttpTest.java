package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.support.SynopsisCorpusFixture;
import com.evidence.rag.support.VideoCompilationFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, authority, durable jobs and provider protocol; media compilation is synthetic. */
class SynopsisHttpTest {
  @TempDir Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("embedding", "b".repeat(64), "model-v1", 2);

  @Test
  void fourModalitiesPersistAndReopenTypedOriginalSourcesAcrossApplicationRestart()
      throws Exception {
    try (var server = new ModelServer();
        var app = new Application(directory, server)) {
      assertTrue(app.context.getBeansOfType(TextModels.class).isEmpty());
      assertTrue(app.context.getBeansOfType(VisionModels.class).isEmpty());
      assertTrue(app.context.getBeansOfType(RetrievalProjection.class).isEmpty());
      var fixture = new SynopsisCorpusFixture(app.store(), OWNER, TARGET);
      var publications =
          List.of(
              fixture.text("设备维护记录：等待五秒。😀"),
              fixture.image(false),
              fixture.image(true),
              fixture.audio(List.of("维护前关闭开关。", "最后等待五秒。")),
              fixture.video(2));
      var saved = new LinkedHashMap<String, JsonNode>();
      var kinds = new HashSet<String>();
      for (var publication : publications) {
        String path = "/v1/documents/" + publication.documentId() + "/synopsis";
        var created = app.request("POST", path, "owner", null, null);
        assertEquals(202, created.statusCode(), app.text(created));
        var receipt = app.json(created);
        assertEquals(publication.documentId(), receipt.path("document_id").asString());
        assertEquals(publication.publicationId(), receipt.path("publication_id").asString());
        assertEquals(7, receipt.size(), "Only the public task receipt fields");
        var task = app.await(receipt.path("task_id").asString());
        assertEquals("available", task.path("state").asString(), task.toString());
        var response = app.request("GET", path, "owner", null, null);
        assertEquals(200, response.statusCode(), app.text(response));
        var synopsis = app.json(response);
        assertEquals("available", synopsis.path("status").asString());
        assertEquals(publication.sourceRevisionId(), synopsis.path("revision_id").asString());
        assertEquals(publication.sourceSha256(), synopsis.path("source_sha256").asString());
        assertFalse(app.text(response).contains("synthetic-summary-credential"));
        assertFalse(app.text(response).contains("token"));
        assertFalse(app.text(response).contains("base64"));
        for (var entry : synopsis.path("entries")) {
          for (var reference : entry.path("evidence")) {
            String sourceUrl = reference.path("source_url").asString();
            var sourceResponse = app.request("GET", sourceUrl, "owner", null, null);
            assertEquals(200, sourceResponse.statusCode(), app.text(sourceResponse));
            var source = app.json(sourceResponse);
            String kind = source.path("kind").asString();
            kinds.add(kind);
            assertEquals(reference.path("evidence_id"), source.path("evidence_id"));
            assertEquals(reference.path("sha256"), source.path("sha256"));
            assertEquals(entry.path("ordinal"), source.path("entry_ordinal"));
            assertEquals(reference.path("ordinal"), source.path("source_ordinal"));
            assertFalse(source.has("content"));
            assertFalse(source.has("frame"));
            var content =
                app.request("GET", source.path("content_url").asString(), "owner", null, null);
            assertEquals(200, content.statusCode());
            assertEquals(publication.sourceSha256(), ModelValues.sha256(content.body()));
            assertEquals("no-store", content.headers().firstValue("Cache-Control").orElseThrow());
            var range =
                app.request(
                    "GET", source.path("content_url").asString(), "owner", null, "bytes=0-3");
            assertEquals(206, range.statusCode());
            assertArrayEquals(java.util.Arrays.copyOf(content.body(), 4), range.body());
            if (!source.path("frame_url").isNull()) {
              var frame =
                  app.request("GET", source.path("frame_url").asString(), "owner", null, null);
              assertEquals(200, frame.statusCode());
              assertArrayEquals(VideoCompilationFixture.image().content(), frame.body());
              assertTrue(
                  source.path("locator").path("end_us").asLong()
                      > source.path("locator").path("start_us").asLong());
            }
            if (kind.equals("video_ocr") || kind.equals("image_ocr")) {
              assertFalse(source.path("locator").path("regions").isEmpty());
              assertEquals("machine_ocr", source.path("proof_origin").asString());
              assertEquals(2, source.path("locator").path("width").asInt());
              assertEquals(2, source.path("locator").path("height").asInt());
            }
            var locator = source.path("locator");
            if (kind.equals("audio_transcript") || kind.equals("video_transcript")) {
              long ordinal = locator.path("span_ordinal").asLong();
              assertEquals(ordinal * 1_000_000, locator.path("start_us").asLong());
              assertEquals((ordinal + 1) * 1_000_000, locator.path("end_us").asLong());
              assertEquals("server_chunk", source.path("time_precision").asString());
            } else if (kind.equals("video_frame") || kind.equals("video_ocr")) {
              long ordinal = locator.path("frame_ordinal").asLong();
              assertEquals(ordinal * 1_000_000, locator.path("start_us").asLong());
              assertEquals(ordinal * 1_000_000 + 200_000, locator.path("end_us").asLong());
              assertEquals(2, locator.path("width").asInt());
              assertEquals(2, locator.path("height").asInt());
              assertEquals("frame_interval", source.path("time_precision").asString());
            }
            if (!reference.path("time").isNull()) {
              assertEquals(reference.path("time").path("start_us"), locator.path("start_us"));
              assertEquals(reference.path("time").path("end_us"), locator.path("end_us"));
            }
            assertEquals(
                404,
                app.request(
                        "GET",
                        source.path("content_url").asString(),
                        "stranger",
                        null,
                        "bytes=999999999-")
                    .statusCode());
          }
        }
        saved.put(publication.documentId(), synopsis);
        var repeated = app.json(app.request("POST", path, "owner", null, null));
        assertEquals(receipt.path("task_id"), repeated.path("task_id"));
      }
      assertEquals(
          java.util.Set.of(
              "text",
              "image",
              "image_ocr",
              "audio_transcript",
              "video_frame",
              "video_transcript",
              "video_ocr"),
          kinds);
      assertTrue(
          server.requests.stream()
              .noneMatch(data -> data.toString().contains("Misleading caption")));
      int calls = server.requests.size();
      app.restart();
      for (var savedEntry : saved.entrySet()) {
        assertEquals(
            savedEntry.getValue(),
            app.json(
                app.request(
                    "GET",
                    "/v1/documents/" + savedEntry.getKey() + "/synopsis",
                    "owner",
                    null,
                    null)));
        String sourceUrl =
            savedEntry
                .getValue()
                .path("entries")
                .get(0)
                .path("evidence")
                .get(0)
                .path("source_url")
                .asString();
        assertEquals(200, app.request("GET", sourceUrl, "owner", null, null).statusCode());
      }
      assertEquals(
          calls,
          server.requests.size(),
          "Reading persisted summaries and restarting must not call models");
      assertEquals(503, app.request("GET", "/health/ready", null, null, null).statusCode());
      assertEquals(404, app.request("POST", "/v1/answers", "owner", null, null).statusCode());
    }
  }

  @Test
  void refusalLeavesIndexPublishedAndRequestValidationPrecedesAnyModelWork() throws Exception {
    try (var server = new ModelServer();
        var app = new Application(directory, server)) {
      var publication = new SynopsisCorpusFixture(app.store(), OWNER, TARGET).text("设备维护记录。");
      String path = "/v1/documents/" + publication.documentId() + "/synopsis";
      var missingIdentity = app.request("POST", path, null, null, null);
      assertEquals(422, missingIdentity.statusCode());
      assertEquals("invalid_identity", app.json(missingIdentity).path("error_code").asString());
      assertEquals(404, app.request("POST", path, "stranger", null, null).statusCode());
      var unexpectedBody = app.request("POST", path, "owner", "{}", null);
      assertEquals(422, unexpectedBody.statusCode());
      assertEquals("invalid_request", app.json(unexpectedBody).path("error_code").asString());
      var unexpectedQuery = app.request("POST", path + "?extra=1", "owner", null, null);
      assertEquals(422, unexpectedQuery.statusCode());
      assertEquals("invalid_request", app.json(unexpectedQuery).path("error_code").asString());
      assertTrue(server.requests.isEmpty());
      server.refuse.set(true);
      var task =
          app.await(
              app.json(app.request("POST", path, "owner", null, null)).path("task_id").asString());
      assertEquals("unavailable", task.path("state").asString());
      assertEquals("model_refused", task.path("error_code").asString());
      assertEquals(
          publication,
          app.store()
              .transaction(
                  () ->
                      new SynopsisMaterialRepository(app.store())
                          .publication(OWNER, publication.documentId())
                          .orElseThrow()));
      assertEquals(1, server.requests.size());
      assertEquals(404, app.request("GET", path, "owner", null, null).statusCode());
    }
  }

  private static final class Application implements AutoCloseable {
    final Path directory;
    final ModelServer model;
    final HttpClient client = HttpClient.newHttpClient();
    ConfigurableApplicationContext context;
    String base;

    Application(Path directory, ModelServer model) {
      this.directory = directory;
      this.model = model;
      start();
    }

    void start() {
      assertNotNull(directory);
      var app = new SpringApplication(RagApplication.class);
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      app.setEnvironment(environment);
      context =
          app.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + directory,
              "--rag.ingestion.enabled=false",
              "--rag.indexing.enabled=false",
              "--rag.answers.enabled=false",
              "--rag.synopsis.enabled=true",
              "--rag.synopsis.base-url=" + model.endpoint(),
              "--rag.synopsis.model=synthetic-summary",
              "--rag.synopsis.api-key=synthetic-summary-credential",
              "--rag.synopsis.allow-loopback-http=true",
              "--rag.synopsis.deadline-ms=5000",
              "--rag.synopsis.budget-ms=20000");
      assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
      base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    void restart() {
      context.close();
      start();
    }

    SqliteAuthorityStore store() {
      return context.getBean(SqliteAuthorityStore.class);
    }

    HttpResponse<byte[]> request(
        String method, String path, String principal, String body, String range) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(20))
              .header("Origin", base);
      if (principal != null) {
        request.header("X-Workspace-Id", "org-main").header("X-Principal-Id", principal);
      }
      if (range != null) {
        request.header("Range", range);
      }
      return client.send(
          request
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode await(String id) throws Exception {
      long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      do {
        var response = request("GET", "/v1/synopsis-tasks/" + id, "owner", null, null);
        assertEquals(200, response.statusCode(), text(response));
        var task = json(response);
        if (!List.of("queued", "processing").contains(task.path("state").asString())) {
          return task;
        }
        Thread.sleep(30);
      } while (System.nanoTime() < end);
      fail("Synopsis did not finish");
      throw new AssertionError();
    }

    JsonNode json(HttpResponse<byte[]> response) {
      return JSON.readTree(response.body());
    }

    String text(HttpResponse<byte[]> response) {
      return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
      if (context != null) {
        context.close();
      }
    }
  }

  private static final class ModelServer implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final AtomicBoolean refuse = new AtomicBoolean();

    ModelServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/chat/completions", this::respond);
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    void respond(HttpExchange exchange) throws IOException {
      var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
      var data =
          JSON.readTree(
              request.path("messages").get(1).path("content").get(0).path("text").asString());
      requests.add(data);
      Map<String, Object> result;
      if (data.has("statement")) {
        var ids = new ArrayList<String>();
        data.path("evidence").forEach(item -> ids.add(item.path("evidence_id").asString()));
        result = Map.of("supported", true, "contributing_evidence_ids", ids);
      } else if (refuse.get()) {
        result = Map.of("refused", true, "items", List.of());
      } else {
        var items = new ArrayList<Map<String, Object>>();
        var first = data.path("evidence").get(0);
        items.add(item("overview", first));
        items.add(item("term", first));
        for (var evidence : data.path("evidence")) {
          items.add(item("topic", evidence));
          String kind = evidence.path("kind").asString();
          if (kind.startsWith("video_") || kind.equals("audio_transcript")) {
            items.add(item("timeline", evidence));
          }
        }
        result = Map.of("refused", false, "items", items);
      }
      byte[] response =
          JSON.writeValueAsBytes(
              Map.of(
                  "choices",
                  List.of(
                      Map.of(
                          "index",
                          0,
                          "finish_reason",
                          "stop",
                          "message",
                          Map.of(
                              "role", "assistant", "content", JSON.writeValueAsString(result))))));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    }

    private static Map<String, Object> item(String section, JsonNode evidence) {
      String text = evidence.has("text") ? evidence.path("text").asString() : "画面含蓝色区域。";
      return Map.of(
          "section",
          section,
          "text",
          text,
          "evidence_ids",
          List.of(evidence.path("evidence_id").asString()));
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
