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
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
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
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native English OCR acceptance; ASR/VLM/text/Milvus are local protocol fixtures, not quality eval.
 */
class VideoOcrAnswersNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "video-ocr-owner");
  private static final String STATUS = "Project A's status is operational.";
  private static final String BUDGET = "Project A's budget is 650 USD.";
  private static final String QUESTION = "What is Project A's status? What is Project A's budget?";
  private static final String TRANSCRIPT = "重启等待时间是5秒。";
  private static final String COLOR = "指示灯的颜色是蓝色。";
  private static final String CAPTION =
      "A blue square in a presentation. No status or budget facts.";
  @TempDir static Path directory;
  private String base;
  private HttpClient client;

  @Test
  void nativeFrameTextAnswersCompleteQuestionWithRealBoxesTimeAndOriginalReadback()
      throws Exception {
    assertNotNull(directory);
    Path ffmpeg = configured("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configured("RAG_VIDEO_DECODER_IT_FFPROBE");
    Path tesseract = configured("RAG_IMAGE_OCR_IT_EXECUTABLE");
    String ocrRevision = System.getenv("RAG_IMAGE_OCR_IT_REVISION");
    assertNotNull(ocrRevision, "Explicit native OCR revision is required");
    Path data = directory.resolve("video-ocr-answers");
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
            kind.equals("EMBEDDING") ? "fixture-model" : "video-ocr-text-fixture");
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
              "--rag.video.ocr.enabled=true",
              "--rag.video.ocr.executable=" + tesseract,
              "--rag.video.ocr.language=eng",
              "--rag.video.ocr.revision=" + ocrRevision,
              "--rag.video.ocr.deadline-ms=15000",
              "--rag.video.asr.base-url=" + models.endpoint(),
              "--rag.video.asr.model=video-asr-fixture",
              "--rag.video.asr.api-key=" + models.key,
              "--rag.video.asr.allow-loopback-http=true",
              "--rag.video.vision.base-url=" + models.endpoint(),
              "--rag.video.vision.model=video-vlm-fixture",
              "--rag.video.vision.api-key=" + models.key,
              "--rag.video.vision.allow-loopback-http=true",
              "--rag.indexing.timeout-ms=15000",
              "--rag.answers.timeout-ms=15000")) {
        assertEquals(data, app.getBean(RagProperties.class).dataDirectory());
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        assertTrue(models.requests.isEmpty());
        assertTrue(indexing.requests.isEmpty());
        var video = upload("presentation.mp4", "video/mp4", original);
        var other =
            upload(
                "selected.txt",
                "application/octet-stream",
                "Noncandidate selected material.".getBytes(StandardCharsets.UTF_8));
        var store = app.getBean(SqliteAuthorityStore.class);
        var ingestion = app.getBean(IngestionRepository.class);
        var compilation =
            store.transaction(() -> ingestion.findVideoCompilation(video.revision()).orElseThrow());
        assertNotNull(compilation.ocr());
        assertTrue(compilation.compilerRevision().startsWith("java-video-compiler-v2:"));
        assertEquals(compilation.frames().size(), compilation.ocr().frames().size());
        assertTrue(
            compilation.ocr().frames().stream()
                .anyMatch(f -> f.text().contains(STATUS) && f.text().contains(BUDGET)));
        assertTrue(
            compilation.ocr().frames().stream()
                .anyMatch(
                    f -> f.text().isEmpty() && f.segments().isEmpty() && f.regions().isEmpty()));
        assertArrayEquals(original, store.transaction(() -> ingestion.original(video.document())));

        // Real indexing creates caption, ASR and OCR rows in the same generation. This fixture's
        // existing search returns every matching physical ID; it has no OCR-specific filtering.
        var videoRows = new ArrayList<JsonNode>();
        for (var request : indexing.committedUpserts) {
          for (var row : request.body().path("data")) {
            if (row.path("document_id").asString().equals(video.document())) {
              videoRows.add(row);
            }
          }
        }
        assertTrue(videoRows.stream().anyMatch(row -> row.path("text").asString().equals(CAPTION)));
        assertTrue(
            videoRows.stream().anyMatch(row -> row.path("text").asString().equals(TRANSCRIPT)));
        assertTrue(
            videoRows.stream().anyMatch(row -> row.path("text").asString().contains(BUDGET)));
        List<String> selection = List.of(video.document(), other.document());
        int beforeOcr = models.requests.size();
        var answer = answer("ocr", QUESTION, selection);
        assertEquals("answered", answer.path("status").asString(), answer.toString());
        assertTrue(answer.path("answer").asString().contains("650 USD"));
        assertTrue(answer.path("answer").asString().contains("operational"));
        assertFalse(answer.path("citations").isEmpty());
        for (var request : indexing.requests) {
          if (request.path().endsWith("/entities/search")) {
            assertTrue(request.body().path("filter").asString().contains(video.document()));
            assertFalse(request.body().path("filter").asString().contains(other.document()));
            assertTrue(request.body().path("limit").asInt() >= videoRows.size());
          }
        }
        boolean extractionSeen = false;
        for (var request : models.requests.subList(beforeOcr, models.requests.size())) {
          if (request.path().equals("/v1/chat/completions")) {
            var content = JSON.readTree(request.body()).path("messages").get(1).path("content");
            assertTrue(content.isString(), "OCR answers must use text proof, never a VLM frame");
            var input = JSON.readTree(content.asString());
            assertEquals(QUESTION, input.path("question").asString());
            assertFalse(input.toString().contains(CAPTION));
            assertFalse(input.toString().contains(TRANSCRIPT));
            assertTrue(input.toString().contains("650 USD"));
            extractionSeen = true;
          }
        }
        assertTrue(extractionSeen);
        boolean statusLocated = false;
        boolean budgetLocated = false;
        for (var citation : answer.path("citations")) {
          assertEquals(video.document(), citation.path("document_id").asString());
          assertEquals(video.revision(), citation.path("revision_id").asString());
          assertEquals(ModelValues.sha256(original), citation.path("source_sha256").asString());
          assertEquals("video_frame_ocr", citation.path("kind").asString());
          assertEquals("machine_ocr", citation.path("proof_origin").asString());
          assertEquals("frame_interval", citation.path("time_precision").asString());
          assertTrue(citation.path("group_id").isNull());
          assertTrue(citation.path("transcript").isNull());
          assertFalse(citation.has("page"));
          assertFalse(citation.has("scene_id"));
          var ocr = citation.path("ocr");
          String quote = ocr.path("quote").asString();
          assertEquals(
              ModelValues.sha256(quote.getBytes(StandardCharsets.UTF_8)),
              ocr.path("quote_sha256").asString());
          assertEquals(compilation.ocr().ocrRevision(), ocr.path("ocr_revision").asString());
          var frame = citation.path("frame");
          var saved =
              compilation.frames().stream()
                  .map(f -> f.frame())
                  .filter(f -> f.image().sha256().equals(frame.path("frame_sha256").asString()))
                  .findFirst()
                  .orElseThrow();
          var savedOcr = compilation.ocr().frames().get(saved.ordinal());
          int start = ocr.path("start_code_point").asInt();
          int end = ocr.path("end_code_point").asInt();
          assertEquals(
              quote, new String(savedOcr.text().codePoints().toArray(), start, end - start));
          assertEquals(saved.presentationUs(), citation.path("start_us").asLong());
          assertEquals(
              saved.presentationUs() + saved.durationUs(), citation.path("end_us").asLong());
          assertEquals(saved.presentationUs(), frame.path("frame_us").asLong());
          assertEquals(saved.durationUs(), frame.path("duration_us").asLong());
          assertTrue(
              saved.durationUs() < 1_000_000,
              "A frame interval must not be extended to the next selected frame");
          var expectedRegions =
              savedOcr.regions().stream().filter(r -> r.start() < end && r.end() > start).toList();
          assertEquals(JSON.readTree(JSON.writeValueAsBytes(expectedRegions)), ocr.path("regions"));
          assertFalse(ocr.path("regions").isEmpty());
          for (var region : ocr.path("regions")) {
            int regionStart = region.path("start").asInt();
            int regionEnd = region.path("end").asInt();
            assertTrue(regionStart < end && regionEnd > start);
            assertTrue(region.path("left").asInt() >= 20 && region.path("right").asInt() < 1200);
            assertTrue(region.path("top").asInt() >= 40 && region.path("bottom").asInt() < 210);
            String word =
                new String(
                    savedOcr.text().codePoints().toArray(), regionStart, regionEnd - regionStart);
            statusLocated |= word.equals("operational.") || word.equals("operational");
            budgetLocated |= word.equals("650");
          }
          String url = citation.path("source_url").asString();
          assertEquals(citation, json("GET", url, null, null, 200).path("citation"));
          assertArrayEquals(
              saved.image().content(), send("GET", url + "/frame", null, null, null, 200).body());
          assertArrayEquals(original, send("GET", url + "/content", null, null, null, 200).body());
          var range = send("GET", url + "/content", null, null, "bytes=3-9", 206);
          assertArrayEquals(Arrays.copyOfRange(original, 3, 10), range.body());
          assertEquals(
              "bytes 3-9/" + original.length,
              range.headers().firstValue("Content-Range").orElseThrow());
          assertEquals("video/mp4", range.headers().firstValue("Content-Type").orElseThrow());
        }
        assertTrue(statusLocated, "The status word must have its real pixel box");
        assertTrue(budgetLocated, "The amount must have its real pixel box");
        // Mixed generations continue to support the old proof contracts, not an OCR-only branch.
        for (String mode : List.of("visual", "transcript", "joint")) {
          String question =
              switch (mode) {
                case "visual" -> "指示灯的颜色是什么？";
                case "transcript" -> "重启等待时间是多少？";
                default -> VideoAnswerFixture.QUESTION;
              };
          var legacy = answer(mode, question, selection);
          assertEquals("answered", legacy.path("status").asString(), legacy.toString());
          assertEquals(mode.equals("joint") ? 2 : 1, legacy.path("citations").size());
          for (var citation : legacy.path("citations")) {
            assertTrue(citation.path("ocr").isNull());
            assertFalse(citation.path("group_id").isNull());
            assertEquals(
                citation,
                json("GET", citation.path("source_url").asString(), null, null, 200)
                    .path("citation"));
            if (!citation.path("frame").isNull()) {
              assertTrue(
                  blueSquare(
                      send(
                              "GET",
                              citation.path("source_url").asString() + "/frame",
                              null,
                              null,
                              null,
                              200)
                          .body()));
            }
          }
        }
        assertOriginalFramesWereUsed(models.requests, compilation);
        int calls = models.requests.size();
        String source = answer.path("citations").get(0).path("source_url").asString();
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

  private JsonNode answer(String mode, String question, List<String> documents) throws Exception {
    return json(
        "POST",
        "/v1/video-answers",
        JSON.writeValueAsBytes(
            Map.of("question", question, "mode", mode, "document_ids", documents)),
        "application/json",
        200);
  }

  private static void assertOriginalFramesWereUsed(
      List<Request> requests, VideoCompilation compilation) throws Exception {
    boolean proofSeen = false;
    for (var request : requests) {
      if (!request.path().equals("/v1/chat/completions")) {
        continue;
      }
      var content = JSON.readTree(request.body()).path("messages").get(1).path("content");
      if (!content.isArray()) {
        continue;
      }
      var input = JSON.readTree(content.get(0).path("text").asString());
      if (!input.has("target_fact")) {
        continue;
      }
      assertFalse(input.toString().contains(CAPTION));
      byte[] sent =
          Base64.getDecoder()
              .decode(content.get(1).path("image_url").path("url").asString().split(",", 2)[1]);
      assertTrue(
          compilation.frames().stream()
              .anyMatch(f -> Arrays.equals(sent, f.frame().image().content())));
      proofSeen |= blueSquare(sent);
    }
    assertTrue(proofSeen);
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
    fail("Synthetic video OCR job did not finish");
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

  private static byte[] generate(Path ffmpeg) throws Exception {
    writeFrame(directory.resolve("frame-00.png"), true);
    writeFrame(directory.resolve("frame-01.png"), false);
    Path output = directory.resolve("presentation.mp4");
    var builder =
        new ProcessBuilder(
                ffmpeg.toString(),
                "-hide_banner",
                "-nostdin",
                "-n",
                "-framerate",
                "1",
                "-i",
                directory.resolve("frame-%02d.png").toString(),
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=16000:duration=2",
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
                "-r",
                "4",
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

  private static void writeFrame(Path output, boolean words) throws Exception {
    var image = new BufferedImage(1400, 400, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      if (words) {
        graphics.setColor(Color.BLUE);
        graphics.fillRect(1250, 280, 100, 80);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 52));
        graphics.drawString(STATUS, 30, 100);
        graphics.drawString(BUDGET, 30, 185);
      }
    } finally {
      graphics.dispose();
    }
    try (var bytes = Files.newOutputStream(output);
        var stream = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(ImageIO.write(image, "png", stream));
    }
  }

  private static boolean blueSquare(byte[] png) throws IOException {
    try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        return false;
      }
      var reader = readers.next();
      try {
        reader.setInput(stream);
        var image = reader.read(0);
        if (image.getWidth() != 1400 || image.getHeight() != 400) {
          return false;
        }
        int pixel = image.getRGB(1300, 320);
        return (pixel & 255) > 180 && ((pixel >> 16) & 255) < 50;
      } finally {
        reader.dispose();
      }
    }
  }

  private static Path configured(String name) {
    if (!"true".equals(System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"))
        || System.getenv(name) == null) {
      throw new IllegalArgumentException(
          "Explicit native video/OCR opt-in and binary paths required");
    }
    Path path = Path.of(System.getenv(name));
    if (!path.isAbsolute() || !Files.isExecutable(path)) {
      throw new IllegalArgumentException("Native binary is unavailable");
    }
    return path;
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
          if (visual && !input.has("target_fact")) {
            result = Map.of("recall_text", CAPTION);
          } else if (visual) {
            boolean color =
                input.path("target_fact").path("canonical_requirement").asString().contains("颜色")
                    && blueSquare(
                        Base64.getDecoder()
                            .decode(
                                content
                                    .get(1)
                                    .path("image_url")
                                    .path("url")
                                    .asString()
                                    .split(",", 2)[1]));
            result =
                input.has("claims")
                    ? Map.of(
                        "complete",
                        color,
                        "support",
                        List.of(Map.of("index", 0, "supported", color)))
                    : Map.of("refused", !color, "claims", color ? List.of(COLOR) : List.of());
          } else {
            var quotes = new ArrayList<Object>();
            boolean transcript = input.has("target_fact");
            boolean relevant =
                !transcript
                    || input
                        .path("target_fact")
                        .path("canonical_requirement")
                        .asString()
                        .contains("等待");
            if (relevant) {
              for (var evidence : input.path("evidence")) {
                String text = evidence.path("text").asString();
                if (!transcript || text.contains(TRANSCRIPT)) {
                  quotes.add(
                      Map.of(
                          "evidence_id",
                          evidence.path("evidence_id").asString(),
                          "quote",
                          transcript ? TRANSCRIPT : text));
                }
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
