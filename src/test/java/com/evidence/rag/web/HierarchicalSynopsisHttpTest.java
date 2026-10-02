package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.service.HierarchicalSynopsisService;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real durable HTTP and local model protocol; compiled media and model judgments are synthetic. */
class HierarchicalSynopsisHttpTest {
  @TempDir Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("embedding", "b".repeat(64), "model-v1", 2);

  @Test
  void longTextAudioAndNineFrameVideoKeepWholeInputsAndTailSourcesAcrossRestart() throws Exception {
    try (var model = new ModelServer();
        var app = new Application(directory, model)) {
      var corpus = new SynopsisCorpusFixture(app.store(), OWNER, TARGET);
      var publications =
          List.of(
              corpus.text("合成记录。".repeat(16000) + "末尾条件：仅试运行允许。"),
              corpus.audio(
                  IntStream.range(0, 17).mapToObj(i -> "文".repeat(3970) + "末尾片段" + i).toList()),
              corpus.video(9));
      var saved = new LinkedHashMap<String, JsonNode>();
      var sourceUrls = new ArrayList<String>();
      boolean lastFrame = false;
      for (var publication : publications) {
        var input =
            app.store()
                .transaction(
                    () -> new SynopsisMaterialRepository(app.store()).document(OWNER, publication));
        assertFalse(input.bounded());
        int before = model.requests.size();
        String path = "/v1/documents/" + publication.documentId() + "/synopsis";
        var response = app.request("POST", path, null);
        assertEquals(202, response.statusCode(), app.text(response));
        var receipt = app.json(response);
        var task = app.await(receipt.path("task_id").asString());
        assertEquals("available", task.path("state").asString(), task.toString());
        response = app.request("GET", path, null);
        assertEquals(200, response.statusCode(), app.text(response));
        var synopsis = app.json(response);
        assertEquals(
            HierarchicalSynopsisService.POLICY_REVISION,
            synopsis.path("policy_revision").asString());
        assertEquals(input.fingerprint(), synopsis.path("input_fingerprint").asString());
        var calls = List.copyOf(model.requests.subList(before, model.requests.size()));
        var leaves =
            calls.stream().filter(r -> r.path("operation").asString().equals("leaf")).toList();
        var reviews =
            calls.stream().filter(r -> r.path("operation").asString().equals("review")).toList();
        assertTrue(leaves.size() >= 2);
        assertEquals(leaves.size(), reviews.size());
        var allIds = new ArrayList<String>();
        for (int i = 0; i < leaves.size(); i++) {
          assertEquals(leaves.get(i).path("batch"), reviews.get(i).path("batch"));
          assertEquals(leaves.get(i).path("evidence"), reviews.get(i).path("evidence"));
          assertEquals(synopsis.path("entries").size(), reviews.get(i).path("items").size());
          leaves.get(i).path("evidence").forEach(e -> allIds.add(e.path("evidence_id").asString()));
        }
        assertEquals(input.evidence().stream().map(SynopsisEvidence::id).toList(), allIds);
        boolean finalOriginalReferenced = false;
        for (var entry : synopsis.path("entries")) {
          for (var ref : entry.path("evidence")) {
            String sourceUrl = ref.path("source_url").asString();
            sourceUrls.add(sourceUrl);
            var sourceResponse = app.request("GET", sourceUrl, null);
            assertEquals(200, sourceResponse.statusCode());
            var source = app.json(sourceResponse);
            assertEquals(ref.path("evidence_id"), source.path("evidence_id"));
            assertEquals(ref.path("sha256"), source.path("sha256"));
            finalOriginalReferenced |=
                input.evidence().getLast().id().equals(source.path("evidence_id").asString());
            var content = app.request("GET", source.path("content_url").asString(), null);
            assertEquals(200, content.statusCode());
            assertEquals(publication.sourceSha256(), ModelValues.sha256(content.body()));
            var range = app.request("GET", source.path("content_url").asString(), "bytes=0-3");
            assertEquals(206, range.statusCode());
            assertArrayEquals(Arrays.copyOf(content.body(), 4), range.body());
            var locator = source.path("locator");
            if (source.path("kind").asString().equals("video_frame")
                && locator.path("frame_ordinal").asInt() == 8) {
              lastFrame = true;
              assertEquals(8_000_000, locator.path("start_us").asLong());
              assertEquals(8_200_000, locator.path("end_us").asLong());
              assertArrayEquals(
                  VideoCompilationFixture.image().content(),
                  app.request("GET", source.path("frame_url").asString(), null).body());
            }
            if (source.path("kind").asString().equals("audio_transcript")
                && locator.path("span_ordinal").asInt() == 16) {
              assertEquals(16_000_000, locator.path("start_us").asLong());
              assertEquals(17_000_000, locator.path("end_us").asLong());
            }
          }
        }
        assertTrue(finalOriginalReferenced);
        saved.put(path, synopsis);
      }
      assertTrue(lastFrame, "Ninth original frame must be navigable, not truncated at eight");
      int count = model.requests.size();
      app.restart();
      for (var entry : saved.entrySet()) {
        assertEquals(entry.getValue(), app.json(app.request("GET", entry.getKey(), null)));
      }
      for (String url : sourceUrls) {
        assertEquals(200, app.request("GET", url, null).statusCode());
      }
      assertEquals(count, model.requests.size(), "Durable reads and restart must not call models");
      assertTrue(
          model.requests.stream()
              .noneMatch(data -> data.toString().contains("Misleading caption")));
    }
  }

  @Test
  void omittedTailRevocationVetoesAvailableSummaryWithoutUnpublishingDocument() throws Exception {
    try (var model = new ModelServer();
        var app = new Application(directory, model)) {
      model.omitTail = true;
      var publication =
          new SynopsisCorpusFixture(app.store(), OWNER, TARGET)
              .text("常规工作说明。".repeat(14000) + "撤销前文授权。仅试运行允许。");
      String path = "/v1/documents/" + publication.documentId() + "/synopsis";
      var receipt = app.json(app.request("POST", path, null));
      var task = app.await(receipt.path("task_id").asString());
      assertEquals("unavailable", task.path("state").asString());
      assertEquals("unsupported_claims", task.path("error_code").asString());
      assertEquals(404, app.request("GET", path, null).statusCode());
      assertEquals(
          publication,
          app.store()
              .transaction(
                  () ->
                      new SynopsisMaterialRepository(app.store())
                          .publication(OWNER, publication.documentId())
                          .orElseThrow()));
      assertTrue(
          model.requests.stream()
              .anyMatch(data -> data.path("operation").asString().equals("verify")));
      assertTrue(
          model.requests.stream()
              .filter(data -> data.path("operation").asString().equals("review"))
              .anyMatch(data -> data.path("evidence").toString().contains("撤销前文授权")));
      assertTrue(
          model.requests.stream()
              .filter(data -> data.path("operation").asString().equals("reduce"))
              .noneMatch(data -> data.toString().contains("撤销前文授权")),
          "Candidate summaries intentionally omit the revocation");
      int count = model.requests.size();
      app.restart();
      assertEquals(
          "unavailable", app.await(receipt.path("task_id").asString()).path("state").asString());
      assertEquals(count, model.requests.size());
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
      var application = new SpringApplication(RagApplication.class);
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      application.setEnvironment(environment);
      context =
          application.run(
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
              "--rag.synopsis.model=synthetic-hierarchy",
              "--rag.synopsis.api-key=synthetic-hierarchy-credential",
              "--rag.synopsis.allow-loopback-http=true",
              "--rag.synopsis.deadline-ms=5000",
              "--rag.synopsis.budget-ms=60000");
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

    HttpResponse<byte[]> request(String method, String path, String range) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(20))
              .header("Origin", base)
              .header("X-Workspace-Id", "org-main")
              .header("X-Principal-Id", "owner");
      if (range != null) {
        request.header("Range", range);
      }
      return client.send(
          request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode await(String id) throws Exception {
      long end = System.nanoTime() + Duration.ofSeconds(25).toNanos();
      do {
        var response = request("GET", "/v1/synopsis-tasks/" + id, null);
        assertEquals(200, response.statusCode(), text(response));
        var task = json(response);
        if (!List.of("queued", "processing").contains(task.path("state").asString())) {
          return task;
        }
        Thread.sleep(30);
      } while (System.nanoTime() < end);
      fail("Long synopsis did not finish");
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
    volatile boolean omitTail;

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
      var result =
          switch (data.path("operation").asString()) {
            case "verify" -> {
              var ids = new ArrayList<String>();
              data.path("evidence").forEach(e -> ids.add(e.path("evidence_id").asString()));
              yield Map.of("supported", true, "contributing_evidence_ids", ids);
            }
            case "review" -> {
              boolean compatible =
                  !omitTail || !data.path("evidence").toString().contains("撤销前文授权");
              yield Map.of(
                  "complete",
                  true,
                  "items",
                  IntStream.range(0, data.path("items").size())
                      .mapToObj(i -> Map.of("index", i, "compatible", compatible))
                      .toList());
            }
            case "reduce" -> {
              var items = new LinkedHashMap<String, Map<String, Object>>();
              data.path("derived_nodes")
                  .forEach(
                      node ->
                          node.path("items")
                              .forEach(
                                  item -> {
                                    var ids = new ArrayList<String>();
                                    item.path("evidence_ids").forEach(id -> ids.add(id.asString()));
                                    String section = item.path("section").asString(),
                                        text = item.path("text").asString();
                                    items.put(
                                        section + ":" + text,
                                        Map.of(
                                            "section", section, "text", text, "evidence_ids", ids));
                                  }));
              yield Map.of("refused", false, "items", new ArrayList<>(items.values()));
            }
            default -> {
              var evidence = data.path("evidence");
              var lastByKind = new LinkedHashMap<String, JsonNode>();
              evidence.forEach(e -> lastByKind.put(e.path("kind").asString(), e));
              var items = new ArrayList<Map<String, Object>>();
              items.add(item("overview", "概览", evidence.get(0)));
              items.add(item("term", "术语", evidence.get(evidence.size() - 1)));
              lastByKind.forEach(
                  (kind, e) -> {
                    items.add(item("topic", kind, e));
                    if (kind.startsWith("video_") || kind.equals("audio_transcript")) {
                      items.add(item("timeline", kind, e));
                    }
                  });
              yield Map.of("refused", false, "items", items);
            }
          };
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

    static Map<String, Object> item(String section, String text, JsonNode evidence) {
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
