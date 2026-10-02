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
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.parser.ProcessVideoDecoder;
import com.sun.net.httpserver.HttpServer;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real video/native/Spring/SQLite/index worker; model and Milvus servers are protocol fixtures. */
class VideoPublicationNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "video-owner");
  @TempDir static Path directory;
  private String base;
  private HttpClient http;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void realVideoUploadCompilesAndPublishesTheCompleteTimelineWithoutOpeningAnswers(boolean audio)
      throws Exception {
    Path ffmpeg = configuredPath("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VIDEO_DECODER_IT_FFPROBE");
    assertNotNull(directory);
    Path data = directory.resolve(audio ? "with-audio" : "without-audio");
    byte[] original = generate(ffmpeg, audio);
    try (var indexing = new IndexingTestServer();
        var models = new MediaModels();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var decoder = new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15), 1)) {
      var expected = decoder.decode("clip.mp4", "video/mp4", original);
      http = client;
      var defaults = new LinkedHashMap<String, Object>();
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        defaults.put("RAG_" + kind + "_BASE_URL", indexing.endpoint().toString());
        defaults.put("RAG_" + kind + "_MODEL", "fixture-model");
        defaults.put("RAG_" + kind + "_API_KEY", UUID.randomUUID().toString());
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
      try (var context =
          application.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + data,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.answers.enabled=false",
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
              "--rag.video.asr.model=synthetic-video-asr",
              "--rag.video.asr.api-key=" + models.key,
              "--rag.video.asr.allow-loopback-http=true",
              "--rag.video.vision.base-url=" + models.endpoint(),
              "--rag.video.vision.model=synthetic-video-vision",
              "--rag.video.vision.api-key=" + models.key,
              "--rag.video.vision.allow-loopback-http=true",
              "--rag.indexing.timeout-ms=15000")) {
        assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var runtime = json("GET", "/v1/config", null, null, 200);
        var capabilities = new ArrayList<String>();
        runtime.path("capabilities").forEach(value -> capabilities.add(value.asString()));
        assertTrue(capabilities.containsAll(List.of("video_upload", "video_index")));
        assertFalse(capabilities.contains("video_answers"));
        assertFalse(capabilities.contains("answers"));
        assertFalse(capabilities.contains("audio_upload"));
        assertTrue(models.requests.isEmpty());
        assertTrue(indexing.requests.isEmpty());
        var upload = json("POST", "/v1/documents?filename=clip.mp4", original, "video/mp4", 202);
        String document = upload.path("document_id").asString();
        String revision = upload.path("revision_id").asString();
        await("ingestions", upload.path("task_id").asString(), "parsed");
        var store = context.getBean(SqliteAuthorityStore.class);
        var repository = context.getBean(IngestionRepository.class);
        var compilation =
            store.transaction(() -> repository.findVideoCompilation(revision).orElseThrow());
        assertEquals(ModelValues.sha256(original), compilation.sourceSha256());
        assertArrayEquals(original, store.transaction(() -> repository.original(document)));
        assertEquals(expected.timelineOriginUs(), compilation.timelineOriginUs());
        assertEquals(expected.durationUs(), compilation.durationUs());
        assertEquals(expected.frames().size(), compilation.frames().size());
        assertEquals(2, compilation.frames().size());
        var recall = new ArrayList<String>();
        for (int ordinal = 0; ordinal < expected.frames().size(); ordinal++) {
          var frame = compilation.frames().get(ordinal);
          var actual = expected.frames().get(ordinal);
          assertEquals(actual.presentationUs(), frame.frame().presentationUs());
          assertEquals(actual.durationUs(), frame.frame().durationUs());
          assertArrayEquals(actual.image().content(), frame.frame().image().content());
          recall.add(frame.recall().recallText());
        }
        assertEquals(audio, compilation.audio() != null);
        if (audio) {
          assertEquals(expected.audio().pcm().length / 2, compilation.audio().sampleCount());
          assertEquals(
              expected.audio().durationMs(), compilation.audio().spans().getLast().endMs());
          assertEquals("", compilation.audio().spans().get(1).text());
          compilation.audio().spans().stream()
              .filter(span -> !span.text().isBlank())
              .forEach(span -> recall.add(span.text()));
        }
        var text = context.getBean(IngestionService.class).parsedEvidence(OWNER, document);
        assertTrue(text.pages().isEmpty());
        assertTrue(text.segments().isEmpty());
        var queued = json("POST", "/v1/documents/" + document + "/index", null, null, 202);
        var indexed = await("indexings", queued.path("task_id").asString(), "indexed");
        var snapshot =
            context
                .getBean(EvidenceService.class)
                .snapshot(
                    OWNER,
                    DocumentSelection.selected(List.of(document)),
                    context.getBean(IndexTarget.class));
        assertEquals(1, snapshot.publications().size());
        assertEquals(
            indexed.path("index_publication_id").asString(),
            snapshot.publications().getFirst().publicationId());
        assertEquals(recall.size(), snapshot.publications().getFirst().segmentCount());
        var embedded = new ArrayList<String>();
        indexing.requests.stream()
            .filter(request -> request.path().equals("/embeddings"))
            .forEach(
                request ->
                    request.body().path("input").forEach(value -> embedded.add(value.asString())));
        assertEquals(recall, embedded);
        var descriptions =
            models.requests.stream()
                .filter(request -> request.path.equals("/v1/chat/completions"))
                .toList();
        assertEquals(expected.frames().size(), descriptions.size());
        for (int index = 0; index < descriptions.size(); index++) {
          String url =
              JSON.readTree(descriptions.get(index).body)
                  .path("messages")
                  .get(1)
                  .path("content")
                  .get(1)
                  .path("image_url")
                  .path("url")
                  .asString();
          assertArrayEquals(
              expected.frames().get(index).image().content(),
              Base64.getDecoder().decode(url.substring(22)));
        }
        assertEquals(
            audio ? compilation.audio().spans().size() : 0,
            models.requests.stream()
                .filter(request -> request.path.endsWith("/audio/transcriptions"))
                .count());
      }
    }
  }

  private JsonNode await(String resource, String id, String expected) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < until) {
      var task = json("GET", "/v1/" + resource + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(task.path("state").asString())) {
        assertEquals(expected, task.path("state").asString(), task.toString());
        return task;
      }
      Thread.sleep(40);
    }
    return fail("Synthetic video task did not finish");
  }

  private JsonNode json(String method, String path, byte[] body, String type, int status)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("X-Workspace-Id", OWNER.workspaceId())
            .header("X-Principal-Id", OWNER.principalId())
            .header("Origin", base);
    if (type != null) {
      request.header("Content-Type", type);
    }
    var response =
        http.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(
        status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private static byte[] generate(Path ffmpeg, boolean audio) throws Exception {
    Path output = directory.resolve(audio ? "with-audio.mp4" : "without-audio.mp4");
    var args =
        new ArrayList<>(
            List.of(
                ffmpeg.toString(),
                "-hide_banner",
                "-nostdin",
                "-n",
                "-f",
                "lavfi",
                "-i",
                "color=c=red:s=96x64:r=4:d=2"));
    if (audio) {
      args.addAll(
          List.of(
              "-itsoffset",
              "0.5",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=16000:duration=1",
              "-map",
              "0:v",
              "-map",
              "1:a",
              "-c:a",
              "aac"));
    }
    args.addAll(
        List.of(
            "-c:v",
            "libx264",
            "-threads",
            "1",
            "-bf",
            "0",
            "-pix_fmt",
            "yuv420p",
            output.toString()));
    var builder =
        new ProcessBuilder(args)
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

  private static Path configuredPath(String name) {
    if (!"true".equals(System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native video opt-in is required");
    }
    String path = System.getenv(name);
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Explicit native video binary path is required");
    }
    return Path.of(path);
  }

  private record Request(String path, byte[] body) {}

  private static final class MediaModels implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final String key = UUID.randomUUID().toString();
    private int asrCalls;

    MediaModels() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/v1/",
          exchange -> {
            try (exchange) {
              String path = exchange.getRequestURI().getPath();
              byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
              if (!"POST".equals(exchange.getRequestMethod())
                  || body.length > 1_048_576
                  || !("Bearer " + key)
                      .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(400, -1);
                return;
              }
              requests.add(new Request(path, body));
              Object result;
              if (path.equals("/v1/audio/transcriptions")) {
                result =
                    Map.of("text", asrCalls++ == 1 ? "" : "合成音轨转录。", "start", 999, "end", 9999);
              } else if (path.equals("/v1/chat/completions")) {
                result =
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
                                    "role",
                                    "assistant",
                                    "content",
                                    "{\"recall_text\":\"合成画面描述仅供召回。\"}"))));
              } else {
                exchange.sendResponseHeaders(404, -1);
                return;
              }
              byte[] response = JSON.writeValueAsBytes(result);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, response.length);
              exchange.getResponseBody().write(response);
            }
          });
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
