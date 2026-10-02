package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real native/Spring/SQLite/index process; every ASR/VLM/text/Milvus server is a local fixture. */
class VideoAnswersNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "video-answer-owner");
  private static final String TRANSCRIPT = "重启等待时间是5秒。";
  private static final String COLOR = "指示灯的颜色是蓝色。";
  private static final String CAPTION = "误导召回：指示灯是红色，等待99秒。";
  @TempDir static Path directory;
  private String base;
  private HttpClient client;

  @Test
  void uploadedVideoAnswersAllModesWithSealedFramesAndAuthorizedOriginalRange() throws Exception {
    assertNotNull(directory);
    Path ffmpeg = configured("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configured("RAG_VIDEO_DECODER_IT_FFPROBE");
    Path data = directory.resolve("video-answers");
    byte[] original = generate(ffmpeg);
    try (var indexing = new IndexingTestServer();
        var models = new Models();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      client = http;
      var defaults = new LinkedHashMap<String, Object>();
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        defaults.put(
            "RAG_" + kind + "_BASE_URL",
            kind.equals("EMBEDDING") ? indexing.endpoint().toString() : models.endpoint());
        defaults.put(
            "RAG_" + kind + "_MODEL",
            kind.equals("EMBEDDING") ? "fixture-model" : "video-fact-model");
        defaults.put("RAG_" + kind + "_API_KEY", models.key);
      }
      defaults.put("RAG_EMBEDDING_DIMENSIONS", "2");
      defaults.put("RAG_EMBEDDING_REVISION", "fixture-v1");
      defaults.put("RAG_MILVUS_ENDPOINT", indexing.endpoint().toString());
      defaults.put("RAG_MILVUS_TOKEN", UUID.randomUUID().toString());
      defaults.put("RAG_MILVUS_COLLECTION", indexing.settings().projection().collection());
      defaults.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
      defaults.put("RAG_TEXT_DEADLINE_MS", "10000");
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      var application = new SpringApplication(RagApplication.class);
      application.setEnvironment(environment);
      application.setDefaultProperties(defaults);
      try (var app =
          application.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + data,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.answers.enabled=true",
              "--rag.visual.enabled=false",
              "--rag.audio.enabled=false",
              "--rag.image-ocr.enabled=false",
              "--rag.document-removal.enabled=false",
              "--rag.video.enabled=true",
              "--rag.video.ffmpeg-executable=" + ffmpeg,
              "--rag.video.ffprobe-executable=" + ffprobe,
              "--rag.video.decode-deadline-ms=15000",
              "--rag.video.frame-interval-seconds=1",
              "--rag.video.chunk-seconds=1",
              "--rag.video.compilation-budget-ms=30000",
              "--rag.video.asr.base-url=" + models.endpoint(),
              "--rag.video.asr.model=video-asr",
              "--rag.video.asr.api-key=" + models.key,
              "--rag.video.asr.allow-loopback-http=true",
              "--rag.video.vision.base-url=" + models.endpoint(),
              "--rag.video.vision.model=video-vision",
              "--rag.video.vision.api-key=" + models.key,
              "--rag.video.vision.allow-loopback-http=true",
              "--rag.indexing.timeout-ms=15000",
              "--rag.answers.timeout-ms=15000")) {
        assertEquals(data, app.getBean(RagProperties.class).dataDirectory());
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        assertTrue(models.requests.isEmpty());
        assertTrue(indexing.requests.isEmpty());
        var runtime = json("GET", "/v1/config", null, null, 200);
        var capabilities = new ArrayList<String>();
        runtime.path("capabilities").forEach(c -> capabilities.add(c.asString()));
        assertTrue(
            capabilities.containsAll(
                List.of("video_upload", "video_index", "video_answers", "video_sources")));
        assertFalse(capabilities.contains("visual_answers"));
        assertFalse(capabilities.contains("audio_answers"));
        var video = upload("signal.mp4", "video/mp4", original);
        var other =
            upload(
                "selected.txt",
                "application/octet-stream",
                "非候选文档。".getBytes(StandardCharsets.UTF_8));
        var store = app.getBean(SqliteAuthorityStore.class);
        var ingestion = app.getBean(IngestionRepository.class);
        var compilation =
            store.transaction(() -> ingestion.findVideoCompilation(video.revision()).orElseThrow());
        assertEquals(ModelValues.sha256(original), compilation.sourceSha256());
        assertTrue(compilation.frames().size() >= 2);
        assertArrayEquals(original, store.transaction(() -> ingestion.original(video.document())));
        List<String> selection = List.of(video.document(), other.document());
        JsonNode joint = null;
        for (String mode : List.of("visual", "transcript", "joint")) {
          String question =
              switch (mode) {
                case "visual" -> "指示灯的颜色是什么？";
                case "transcript" -> "重启等待时间是多少？";
                default -> VideoAnswerFixture.QUESTION;
              };
          var answer =
              json(
                  "POST",
                  "/v1/video-answers",
                  JSON.writeValueAsBytes(
                      Map.of("question", question, "mode", mode, "document_ids", selection)),
                  "application/json",
                  200);
          assertEquals("answered", answer.path("status").asString(), answer.toString());
          assertFalse(answer.path("answer").asString().contains("红"));
          assertFalse(answer.path("answer").asString().contains("99"));
          assertEquals(mode.equals("joint") ? 2 : 1, answer.path("citations").size());
          if (mode.equals("joint")) {
            joint = answer;
          }
          for (var citation : answer.path("citations")) {
            assertEquals(video.document(), citation.path("document_id").asString());
            assertEquals(video.revision(), citation.path("revision_id").asString());
            assertEquals(ModelValues.sha256(original), citation.path("source_sha256").asString());
            assertFalse(citation.has("page"));
            assertFalse(citation.has("scene_id"));
            String url = citation.path("source_url").asString();
            assertEquals(citation, json("GET", url, null, null, 200).path("citation"));
            assertArrayEquals(
                original, send("GET", url + "/content", null, null, null, 200).body());
            var range = send("GET", url + "/content", null, null, "bytes=3-9", 206);
            assertArrayEquals(Arrays.copyOfRange(original, 3, 10), range.body());
            assertEquals(
                "bytes 3-9/" + original.length,
                range.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("video/mp4", range.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(
                0,
                send("GET", url + "/content", null, null, "bytes=" + original.length + "-", 416)
                    .body()
                    .length);
            if (!citation.path("frame").isNull()) {
              var frame = citation.path("frame");
              byte[] bytes = send("GET", url + "/frame", null, null, null, 200).body();
              assertEquals(frame.path("frame_sha256").asString(), ModelValues.sha256(bytes));
              assertTrue(
                  compilation.frames().stream()
                      .anyMatch(f -> Arrays.equals(bytes, f.frame().image().content())));
              try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                assertTrue(readers.hasNext());
                var reader = readers.next();
                try {
                  reader.setInput(stream);
                  var pixels = reader.read(0);
                  int center = pixels.getRGB(48, 32);
                  assertTrue((center & 255) > 180 && ((center >> 16) & 255) < 50);
                  assertEquals(96, pixels.getWidth());
                  assertEquals(64, pixels.getHeight());
                } finally {
                  reader.dispose();
                }
              }
            }
          }
        }
        assertNotNull(joint);
        assertEquals(
            joint.path("citations").get(0).path("group_id"),
            joint.path("citations").get(1).path("group_id"));
        for (var request : models.requests) {
          if (!request.path().equals("/v1/chat/completions")) {
            continue;
          }
          var content = JSON.readTree(request.body()).path("messages").get(1).path("content");
          var input =
              JSON.readTree(
                  content.isArray() ? content.get(0).path("text").asString() : content.asString());
          if (!input.has("target_fact")) {
            continue;
          }
          assertFalse(input.toString().contains(CAPTION));
          if (content.isArray()) {
            byte[] sent =
                Base64.getDecoder()
                    .decode(
                        content.get(1).path("image_url").path("url").asString().split(",", 2)[1]);
            assertTrue(
                compilation.frames().stream()
                    .anyMatch(f -> Arrays.equals(sent, f.frame().image().content())));
          }
        }
        int calls = models.requests.size();
        var empty =
            json(
                "POST",
                "/v1/video-answers",
                JSON.writeValueAsBytes(
                    Map.of(
                        "question",
                        VideoAnswerFixture.QUESTION,
                        "mode",
                        "joint",
                        "document_ids",
                        List.of())),
                "application/json",
                200);
        assertEquals("abstained", empty.path("status").asString());
        assertTrue(empty.path("citations").isEmpty());
        assertEquals(calls, models.requests.size());
        String source = joint.path("citations").get(0).path("source_url").asString();
        try (var connection =
                DriverManager.getConnection("jdbc:sqlite:" + data.resolve("java-library.db"));
            var statement =
                connection.prepareStatement(
                    "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
          statement.setString(1, other.document());
          statement.setString(2, OWNER.principalId());
          assertEquals(1, statement.executeUpdate());
        }
        json("GET", source, null, null, 404);
        send("GET", source + "/frame", null, null, null, 404);
        send("GET", source + "/content", null, null, "bytes=0-2", 404);
        assertEquals(calls, models.requests.size());
      }
    }
  }

  private Uploaded upload(String filename, String mime, byte[] bytes) throws Exception {
    var upload = json("POST", "/v1/documents?filename=" + filename, bytes, mime, 202);
    await("ingestions", upload.path("task_id").asString(), "parsed");
    var index =
        json(
            "POST",
            "/v1/documents/" + upload.path("document_id").asString() + "/index",
            null,
            null,
            202);
    await("indexings", index.path("task_id").asString(), "indexed");
    return new Uploaded(
        upload.path("document_id").asString(), upload.path("revision_id").asString());
  }

  private void await(String kind, String id, String state) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < end) {
      var result = json("GET", "/v1/" + kind + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(result.path("state").asString())) {
        assertEquals(state, result.path("state").asString(), result.toString());
        return;
      }
      Thread.sleep(40);
    }
    fail("Synthetic video job did not finish");
  }

  private JsonNode json(String method, String path, byte[] body, String type, int status)
      throws Exception {
    return JSON.readTree(send(method, path, body, type, null, status).body());
  }

  private HttpResponse<byte[]> send(
      String method, String path, byte[] body, String type, String range, int status)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("X-Workspace-Id", OWNER.workspaceId())
            .header("X-Principal-Id", OWNER.principalId())
            .header("Origin", base);
    if (type != null) {
      request.header("Content-Type", type);
    }
    if (range != null) {
      request.header("Range", range);
    }
    var result =
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
        result.statusCode(),
        () ->
            "HTTP "
                + method
                + " "
                + path
                + ": "
                + new String(result.body(), StandardCharsets.UTF_8));
    return result;
  }

  private byte[] generate(Path ffmpeg) throws Exception {
    Path output = directory.resolve("signal.mp4");
    var builder =
        new ProcessBuilder(
                ffmpeg.toString(),
                "-hide_banner",
                "-nostdin",
                "-n",
                "-f",
                "lavfi",
                "-i",
                "color=c=black:s=96x64:r=4:d=2,drawbox=x=32:y=16:w=32:h=32:color=blue:t=fill",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=16000:duration=1",
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-c:v",
                "libx264",
                "-threads",
                "1",
                "-bf",
                "0",
                "-c:a",
                "aac",
                "-pix_fmt",
                "yuv420p",
                output.toString())
            .directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    Process process = builder.start();
    process.getOutputStream().close();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS));
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
    if (!"true".equals(System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"))
        || System.getenv(name) == null) {
      throw new IllegalArgumentException("Explicit native video opt-in and binary paths required");
    }
    return Path.of(System.getenv(name));
  }

  private record Uploaded(String document, String revision) {}

  private record Request(String path, byte[] body) {}

  private static final class Models implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final String key = UUID.randomUUID().toString();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

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
        byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
        if (body.length > 1_048_576
            || !("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(path, body));
        Object response;
        if (path.equals("/v1/audio/transcriptions")) {
          response = Map.of("text", TRANSCRIPT);
        } else if (path.equals("/v1/rerank")) {
          var input = JSON.readTree(body);
          var ranks = new ArrayList<Object>();
          for (int i = 0; i < input.path("documents").size(); i++) {
            ranks.add(Map.of("index", i, "relevance_score", 100.0 - i));
          }
          response = Map.of("results", ranks);
        } else if (path.equals("/v1/chat/completions")) {
          var content = JSON.readTree(body).path("messages").get(1).path("content");
          boolean visual = content.isArray();
          var input =
              JSON.readTree(visual ? content.get(0).path("text").asString() : content.asString());
          Object result;
          if (!input.has("target_fact")) {
            result = Map.of("recall_text", CAPTION);
          } else if (visual) {
            boolean color =
                input.path("target_fact").path("canonical_requirement").asString().contains("颜色");
            result =
                input.has("claims")
                    ? Map.of(
                        "complete",
                        color,
                        "support",
                        List.of(Map.of("index", 0, "supported", color)))
                    : Map.of("refused", !color, "claims", color ? List.of(COLOR) : List.of());
          } else {
            boolean wait =
                input.path("target_fact").path("canonical_requirement").asString().contains("等待");
            result =
                Map.of(
                    "refused",
                    !wait,
                    "quotes",
                    wait
                        ? List.of(
                            Map.of(
                                "evidence_id",
                                input.path("evidence").get(0).path("evidence_id").asString(),
                                "quote",
                                TRANSCRIPT))
                        : List.of());
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
        byte[] encoded = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, encoded.length);
        exchange.getResponseBody().write(encoded);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
