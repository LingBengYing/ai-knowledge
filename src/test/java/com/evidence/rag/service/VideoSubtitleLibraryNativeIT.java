package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real FFmpeg and Spring HTTP/SQLite; all providers and Milvus are loopback protocol fixtures. */
class VideoSubtitleLibraryNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BUDGET = "Project A's budget is 650 USD.";
  private static final String TAIL = "Final approval marker is TAIL-917.";
  private static final String QUESTION = "What is Project A's budget?";
  private static final String CAPTION = "A plain red background, no printed words.";
  private static final String ASR = "Unrelated machine tone.";
  @TempDir Path directory;

  @Test
  void embeddedSubtitleOnlyFactSurvivesUploadIndexAnswerSynopsisSourcesRangeAndRestart()
      throws Exception {
    Path ffmpeg = configured("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configured("RAG_VIDEO_DECODER_IT_FFPROBE");
    byte[] original = generate(ffmpeg);
    try (var indexing = new IndexingTestServer();
        var models = new Models();
        var app =
            new Application(directory.resolve("authority"), ffmpeg, ffprobe, indexing, models)) {
      assertTrue(models.requests.isEmpty());
      var upload =
          app.json("POST", "/v1/documents?filename=subtitles.mp4", original, "video/mp4", 202);
      String document = upload.path("document_id").asString();
      String revision = upload.path("revision_id").asString();
      app.await("ingestions", upload.path("task_id").asString(), "parsed");
      var index = app.json("POST", "/v1/documents/" + document + "/index", null, null, 202);
      app.await("indexings", index.path("task_id").asString(), "indexed");
      var store = app.context.getBean(SqliteAuthorityStore.class);
      var compilation =
          store.transaction(
              () ->
                  app.context
                      .getBean(IngestionRepository.class)
                      .findVideoCompilation(revision)
                      .orElseThrow());
      assertTrue(compilation.compilerRevision().startsWith("java-video-compiler-v3:"));
      assertEquals(2, compilation.subtitles().tracks().size());
      assertTrue(
          compilation.frames().stream().allMatch(f -> f.recall().recallText().equals(CAPTION)));
      assertTrue(compilation.audio().spans().stream().allMatch(s -> s.text().equals(ASR)));
      assertEquals(4_500_000, compilation.durationUs());
      var allCues =
          compilation.subtitles().tracks().stream()
              .flatMap(t -> t.cues().stream())
              .filter(c -> !c.text().isBlank())
              .toList();
      assertEquals(3, allCues.size());
      var indexedTexts = new ArrayList<String>();
      for (var request : indexing.committedUpserts) {
        for (var row : request.body().path("data")) {
          if (document.equals(row.path("document_id").asString())) {
            indexedTexts.add(row.path("text").asString());
          }
        }
      }
      assertTrue(indexedTexts.contains(BUDGET));
      assertTrue(indexedTexts.contains(TAIL));
      assertEquals(
          compilation.frames().size() + compilation.audio().spans().size() + 3,
          indexedTexts.size());
      var answer =
          app.json(
              "POST",
              "/v1/video-answers",
              JSON.writeValueAsBytes(
                  Map.of(
                      "mode", "subtitle", "question", QUESTION, "document_ids", List.of(document))),
              "application/json",
              200);
      assertEquals("answered", answer.path("status").asString(), answer.toString());
      assertTrue(answer.path("answer").asString().contains("650 USD"));
      assertFalse(answer.path("citations").isEmpty());
      var citation = answer.path("citations").get(0);
      assertEquals("video_subtitle", citation.path("kind").asString());
      assertEquals("embedded_subtitle", citation.path("proof_origin").asString());
      assertEquals("subtitle_cue", citation.path("time_precision").asString());
      assertEquals(ModelValues.sha256(original), citation.path("source_sha256").asString());
      assertEquals(500000, citation.path("start_us").asLong());
      assertEquals(1250000, citation.path("end_us").asLong());
      assertTrue(citation.path("frame").isNull());
      assertTrue(citation.path("transcript").isNull());
      assertTrue(citation.path("ocr").isNull());
      assertTrue(citation.path("group_id").isNull());
      assertEquals("mov_text", citation.path("subtitle").path("codec").asString());
      assertEquals(
          "subtitle-payload-utf8-v1", citation.path("subtitle").path("text_format").asString());
      String answerSource = citation.path("source_url").asString();
      assertEquals(citation, app.json("GET", answerSource, null, null, 200).path("citation"));
      app.sourceBytes(answerSource + "/content", original);
      app.send("GET", answerSource + "/frame", null, null, null, "owner", 404);

      var task = app.json("POST", "/v1/documents/" + document + "/synopsis", null, null, 202);
      app.await("synopsis-tasks", task.path("task_id").asString(), "available");
      String synopsisPath = "/v1/documents/" + document + "/synopsis";
      var synopsis = app.json("GET", synopsisPath, null, null, 200);
      String synopsisSource =
          synopsis.path("entries").get(0).path("evidence").get(0).path("source_url").asString();
      var source = app.json("GET", synopsisSource, null, null, 200);
      assertEquals(TAIL, source.path("text").asString());
      assertEquals("video_subtitle", source.path("kind").asString());
      assertEquals("embedded_subtitle", source.path("proof_origin").asString());
      assertEquals("subtitle_cue", source.path("time_precision").asString());
      assertEquals(3500000, source.path("locator").path("start_us").asLong());
      assertEquals(4500000, source.path("locator").path("end_us").asLong());
      assertTrue(source.path("frame_url").isNull());
      app.sourceBytes(source.path("content_url").asString(), original);
      assertEquals(allCues.stream().map(c -> c.text()).toList(), models.synopsisSubtitles);
      assertTrue(
          models.requests.stream().anyMatch(r -> r.path().equals("/v1/audio/transcriptions")));
      assertTrue(models.requests.stream().anyMatch(r -> r.path().equals("/v1/rerank")));
      int calls = models.requests.size();
      app.restart();
      assertEquals(citation, app.json("GET", answerSource, null, null, 200).path("citation"));
      assertEquals(synopsis, app.json("GET", synopsisPath, null, null, 200));
      assertEquals(source, app.json("GET", synopsisSource, null, null, 200));
      app.sourceBytes(answerSource + "/content", original);
      app.sourceBytes(source.path("content_url").asString(), original);
      assertEquals(calls, models.requests.size(), "Readback and restart must not call models");
    }
  }

  private byte[] generate(Path ffmpeg) throws Exception {
    Path first = directory.resolve("first.srt"), second = directory.resolve("second.srt");
    Files.writeString(
        first,
        "1\n00:00:00,500 --> 00:00:01,250\n"
            + BUDGET
            + "\n\n2\n00:00:02,000 --> 00:00:03,000\nRelease requires approval.\n");
    Files.writeString(second, "1\n00:00:03,500 --> 00:00:04,500\n" + TAIL + "\n");
    Path output = directory.resolve("subtitles.mp4");
    var builder =
        new ProcessBuilder(
                ffmpeg.toString(),
                "-hide_banner",
                "-nostdin",
                "-n",
                "-f",
                "lavfi",
                "-i",
                "color=c=red:s=160x120:r=4:d=4",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=16000:duration=4",
                "-i",
                first.toString(),
                "-i",
                second.toString(),
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-map",
                "2:s",
                "-map",
                "3:s",
                "-c:v",
                "libx264",
                "-threads",
                "1",
                "-bf",
                "0",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-c:s",
                "mov_text",
                output.toString())
            .directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    var process = builder.start();
    process.getOutputStream().close();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
      }
    }
    return Files.readAllBytes(output);
  }

  private static Path configured(String name) {
    assertEquals("true", System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"));
    assertNotNull(System.getenv(name));
    Path executable = Path.of(System.getenv(name));
    assertTrue(executable.isAbsolute() && Files.isExecutable(executable));
    return executable;
  }

  private static final class Application implements AutoCloseable {
    final Path data;
    final Path ffmpeg;
    final Path ffprobe;
    final IndexingTestServer indexing;
    final Models models;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    ConfigurableApplicationContext context;
    String base;

    Application(Path data, Path ffmpeg, Path ffprobe, IndexingTestServer indexing, Models models) {
      this.data = data;
      this.ffmpeg = ffmpeg;
      this.ffprobe = ffprobe;
      this.indexing = indexing;
      this.models = models;
      start();
    }

    void start() {
      var values = new LinkedHashMap<String, Object>();
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        values.put(
            "RAG_" + kind + "_BASE_URL",
            kind.equals("EMBEDDING") ? indexing.endpoint().toString() : models.endpoint());
        values.put(
            "RAG_" + kind + "_MODEL",
            kind.equals("EMBEDDING") ? "fixture-model" : "subtitle-answer");
        values.put("RAG_" + kind + "_API_KEY", models.key);
      }
      values.put("RAG_EMBEDDING_DIMENSIONS", "2");
      values.put("RAG_EMBEDDING_REVISION", "fixture-v1");
      values.put("RAG_MILVUS_ENDPOINT", indexing.endpoint().toString());
      values.put("RAG_MILVUS_TOKEN", models.key);
      values.put("RAG_MILVUS_COLLECTION", indexing.settings().projection().collection());
      values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
      values.put("RAG_TEXT_DEADLINE_MS", "10000");
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      var spring = new SpringApplication(RagApplication.class);
      spring.setEnvironment(environment);
      spring.setDefaultProperties(values);
      context =
          spring.run(
              "--server.address=127.0.0.1",
              "--server.port=0",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + data,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.answers.enabled=true",
              "--rag.video.enabled=true",
              "--rag.video.subtitles.enabled=true",
              "--rag.video.ocr.enabled=false",
              "--rag.video.ffmpeg-executable=" + ffmpeg,
              "--rag.video.ffprobe-executable=" + ffprobe,
              "--rag.video.frame-interval-seconds=2",
              "--rag.video.chunk-seconds=2",
              "--rag.video.decode-deadline-ms=20000",
              "--rag.video.compilation-budget-ms=30000",
              "--rag.video.asr.base-url=" + models.endpoint(),
              "--rag.video.asr.model=subtitle-asr",
              "--rag.video.asr.api-key=" + models.key,
              "--rag.video.asr.allow-loopback-http=true",
              "--rag.video.vision.base-url=" + models.endpoint(),
              "--rag.video.vision.model=subtitle-vlm",
              "--rag.video.vision.api-key=" + models.key,
              "--rag.video.vision.allow-loopback-http=true",
              "--rag.synopsis.enabled=true",
              "--rag.synopsis.base-url=" + models.endpoint(),
              "--rag.synopsis.model=subtitle-synopsis",
              "--rag.synopsis.api-key=" + models.key,
              "--rag.synopsis.allow-loopback-http=true",
              "--rag.synopsis.budget-ms=30000");
      assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
      base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    void restart() {
      context.close();
      start();
    }

    JsonNode json(String method, String path, byte[] body, String type, int status)
        throws Exception {
      return JSON.readTree(send(method, path, body, type, null, "owner", status).body());
    }

    HttpResponse<byte[]> send(
        String method,
        String path,
        byte[] body,
        String type,
        String range,
        String principal,
        int status)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(30))
              .header("X-Workspace-Id", "org-main")
              .header("X-Principal-Id", principal)
              .header("Origin", base);
      if (type != null) {
        request.header("Content-Type", type);
      }
      if (range != null) {
        request.header("Range", range);
      }
      var response =
          client.send(
              request
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(
          status,
          response.statusCode(),
          () -> method + " " + path + " " + new String(response.body(), StandardCharsets.UTF_8));
      return response;
    }

    void sourceBytes(String path, byte[] original) throws Exception {
      assertArrayEquals(original, send("GET", path, null, null, null, "owner", 200).body());
      var range = send("GET", path, null, null, "bytes=1-4", "owner", 206);
      assertArrayEquals(Arrays.copyOfRange(original, 1, 5), range.body());
      assertEquals(
          "bytes 1-4/" + original.length,
          range.headers().firstValue("Content-Range").orElseThrow());
      send("GET", path, null, null, "bytes=1-4", "stranger", 404);
    }

    void await(String kind, String id, String expected) throws Exception {
      long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
      while (System.nanoTime() < end) {
        var result = json("GET", "/v1/" + kind + "/" + id, null, null, 200);
        if (!List.of("queued", "processing").contains(result.path("state").asString())) {
          assertEquals(expected, result.path("state").asString(), result.toString());
          return;
        }
        Thread.sleep(40);
      }
      fail("Synthetic subtitle job did not complete");
    }

    public void close() {
      if (context != null) {
        context.close();
      }
      client.close();
    }
  }

  private record Request(String path, JsonNode json) {}

  private static final class Models implements AutoCloseable {
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    final String key = UUID.randomUUID().toString();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<String> synopsisSubtitles = new CopyOnWriteArrayList<>();

    Models() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/", this::respond);
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        byte[] bytes = exchange.getRequestBody().readNBytes(2_000_001);
        if (bytes.length > 2_000_000
            || !("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        String path = exchange.getRequestURI().getPath();
        Object response;
        if (path.equals("/v1/audio/transcriptions")) {
          requests.add(new Request(path, null));
          response = Map.of("text", ASR);
        } else {
          var body = JSON.readTree(bytes);
          requests.add(new Request(path, body));
          if (path.equals("/v1/rerank")) {
            var ranks = new ArrayList<Object>();
            for (int i = 0; i < body.path("documents").size(); i++) {
              ranks.add(Map.of("index", i, "relevance_score", 100.0 - i));
            }
            response = Map.of("results", ranks);
          } else if (path.equals("/v1/chat/completions")) {
            var content = body.path("messages").get(1).path("content");
            var input =
                JSON.readTree(
                    content.isArray()
                        ? content.get(0).path("text").asString()
                        : content.asString());
            Object result;
            if (body.path("model").asString().equals("subtitle-synopsis")) {
              var ids = new ArrayList<String>();
              for (var source : input.path("evidence")) {
                if (source.path("text").asString().equals(TAIL)) {
                  ids.add(source.path("evidence_id").asString());
                }
                if (!input.has("statement")
                    && source.path("kind").asString().equals("video_subtitle")) {
                  synopsisSubtitles.add(source.path("text").asString());
                }
              }
              if (input.has("statement")) {
                result =
                    Map.of(
                        "supported",
                        input.path("statement").asString().equals(TAIL) && ids.size() == 1,
                        "contributing_evidence_ids",
                        ids);
              } else {
                var items =
                    List.of("overview", "topic", "term", "timeline").stream()
                        .map(
                            section ->
                                Map.of("section", section, "text", TAIL, "evidence_ids", ids))
                        .toList();
                result = Map.of("refused", false, "items", items);
              }
            } else if (content.isArray()) {
              result = Map.of("recall_text", CAPTION);
            } else {
              var quotes = new ArrayList<Object>();
              for (var source : input.path("evidence")) {
                if (source.path("text").asString().equals(BUDGET)) {
                  quotes.add(
                      Map.of(
                          "evidence_id", source.path("evidence_id").asString(), "quote", BUDGET));
                }
              }
              result = Map.of("refused", quotes.isEmpty(), "quotes", quotes);
            }
            response =
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
                                "role", "assistant", "content", JSON.writeValueAsString(result)))));
          } else {
            exchange.sendResponseHeaders(404, -1);
            return;
          }
        }
        byte[] encoded = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, encoded.length);
        exchange.getResponseBody().write(encoded);
      }
    }

    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
