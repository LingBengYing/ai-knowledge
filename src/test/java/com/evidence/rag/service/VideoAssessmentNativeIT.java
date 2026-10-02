package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.worker.parser.ProcessVideoDecoder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real native input and local model protocols through the group-proof Interface. The synthetic
 * provider replies do not certify ASR/VLM quality, authority publication, or public answer routes.
 */
class VideoAssessmentNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION = "指示灯的颜色是什么？重启等待时间是多少秒？";
  private static final String TRANSCRIPT = "重启等待时间是5秒。";
  private static final String VISUAL_CLAIM = "指示灯的颜色是蓝色。";
  private static final String MISLEADING_RECALL = "指示灯是红色，重启等待时间是99秒。";
  @TempDir Path directory;

  @Test
  void nativeFrameAndItsRealOverlappingTranscriptProveDifferentFactsWithoutUsingCaption()
      throws Exception {
    Path ffmpeg = configuredPath("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VIDEO_DECODER_IT_FFPROBE");
    byte[] original = generate(ffmpeg);
    try (var fixture = new MediaModels();
        var decoder = new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15), 1);
        var asr =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    fixture.endpoint("synthetic-video-asr"), Duration.ofSeconds(5), 65536, true));
        var vision =
            new OpenAiCompatibleVisionModels(
                new OpenAiCompatibleVisionModels.Configuration(
                    fixture.endpoint("synthetic-video-vision"),
                    Duration.ofSeconds(5),
                    65536,
                    true));
        var text =
            new OpenAiCompatibleModels(
                new OpenAiCompatibleModels.Configuration(
                    fixture.endpoint("synthetic-video-text"),
                    fixture.endpoint("synthetic-video-text"),
                    fixture.endpoint("synthetic-video-text"),
                    2,
                    Duration.ofSeconds(5),
                    65536,
                    true))) {
      var compiler =
          new VideoCompilationService(
              decoder,
              new AudioTranscriptionService(asr, 1, Duration.ofSeconds(15)),
              vision,
              Duration.ofSeconds(30));
      var compilation = compiler.compile("signal.mp4", "video/mp4", original, () -> true);
      assertEquals(ModelValues.sha256(original), compilation.sourceSha256());
      assertEquals(decoder.revision(), compilation.decoderRevision());
      assertEquals(compiler.revision(), compilation.compilerRevision());
      assertEquals(0, compilation.timelineOriginUs());
      assertEquals(2_000_000, compilation.durationUs());
      assertNotNull(compilation.audio());
      assertTrue(compilation.audio().spans().stream().allMatch(s -> TRANSCRIPT.equals(s.text())));
      assertTrue(
          compilation.frames().stream()
              .allMatch(frame -> MISLEADING_RECALL.equals(frame.recall().recallText())));

      String revision = "native-video-assessment";
      var evidence = VideoEvidence.fromCompilation(revision, compilation);
      var group = evidence.groups().getFirst();
      var frame = evidence.frames().getFirst();
      var span = evidence.spans().getFirst();
      assertEquals(frame.id(), group.frameId());
      assertEquals(span.id(), group.transcriptSpanId());
      assertEquals(0, frame.material().frame().presentationUs());
      assertEquals(250_000, frame.material().frame().durationUs());
      assertEquals(0, span.span().startMs());
      assertEquals(1000, span.span().endMs());
      assertEquals(
          Math.max(frame.material().frame().presentationUs(), span.span().startMs() * 1000),
          group.startUs());
      assertEquals(
          Math.min(
              frame.material().frame().presentationUs() + frame.material().frame().durationUs(),
              span.span().endMs() * 1000),
          group.endUs());
      assertTrue(group.endUs() > group.startUs());
      byte[] frameBytes = frame.material().frame().image().content();
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(frameBytes))) {
        var readers = ImageIO.getImageReaders(input);
        assertTrue(readers.hasNext());
        var reader = readers.next();
        try {
          reader.setInput(input);
          var image = reader.read(0);
          assertNotNull(image);
          assertEquals(96, image.getWidth());
          assertEquals(64, image.getHeight());
          int center = image.getRGB(48, 32);
          assertTrue((center & 255) > 200, "The actual original frame contains the blue signal");
          assertTrue(((center >>> 16) & 255) < 50);
          assertTrue(((center >>> 8) & 255) < 50);
        } finally {
          reader.dispose();
        }
      }

      int compilationRequests = fixture.requests.size();
      assertEquals(
          compilation.audio().spans().size(),
          fixture.requests.stream()
              .filter(r -> r.path().endsWith("/audio/transcriptions"))
              .count());
      assertEquals(
          compilation.frames().size(),
          fixture.requests.stream().filter(r -> r.path().endsWith("/chat/completions")).count());
      var result =
          new VideoAssessmentService(text, vision, Duration.ofSeconds(15))
              .assess(
                  QUESTION,
                  revision,
                  compilation,
                  group.id(),
                  VideoAssessment.Mode.JOINT,
                  () -> true);
      assertTrue(result.supported(), result.refusalReason());
      assertEquals(compilation.sourceSha256(), result.sourceSha256());
      assertEquals(evidence.manifestSha256(), result.manifestSha256());
      assertEquals(group, result.group());
      assertEquals(text.revision(), result.textModelRevision());
      assertEquals(vision.revision(), result.visionModelRevision());
      assertEquals(2, result.factIds().size());
      assertEquals(2, result.proofs().size());
      var color = result.proofs().getFirst();
      var wait = result.proofs().getLast();
      assertEquals(result.factIds().getFirst(), color.factId());
      assertEquals(1, color.visualSupport());
      assertEquals(0, color.transcriptSupport());
      assertEquals(List.of(VISUAL_CLAIM), color.visualClaims());
      assertEquals(result.factIds().getLast(), wait.factId());
      assertEquals(0, wait.visualSupport());
      assertEquals(1, wait.transcriptSupport());
      assertEquals(1, wait.transcriptQuotes().size());
      var quote = wait.transcriptQuotes().getFirst();
      assertEquals("重启等待时间是5秒", quote.quote());
      assertEquals(span.id(), quote.physicalId());
      String transcriptContext =
          String.join("\n", compilation.audio().spans().stream().map(s -> s.text()).toList());
      assertEquals(
          quote.quote(),
          transcriptContext.substring(
              transcriptContext.offsetByCodePoints(0, quote.start()),
              transcriptContext.offsetByCodePoints(0, quote.end())));
      assertEquals(List.of(wait.factId()), quote.factHashes());
      assertFalse(result.toString().contains(QUESTION));
      assertFalse(color.toString().contains(VISUAL_CLAIM));

      var proofs = fixture.requests.subList(compilationRequests, fixture.requests.size());
      assertEquals(
          5, proofs.size(), "Two drafts, one visual verification and two text extractions");
      var visionFactIds = new HashSet<String>();
      var textFactIds = new HashSet<String>();
      for (var request : proofs) {
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer " + fixture.key, request.authorization());
        var body = JSON.readTree(request.body());
        var content = body.path("messages").get(1).path("content");
        boolean imageRequest = content.isArray();
        var data =
            JSON.readTree(
                imageRequest ? content.get(0).path("text").asString() : content.asString());
        assertEquals(QUESTION, data.path("question").asString());
        String factId = data.path("target_fact").path("id").asString();
        int ordinal = data.path("target_fact").path("ordinal").asInt();
        assertEquals(result.factIds().get(ordinal), factId);
        assertFalse(data.toString().contains(MISLEADING_RECALL));
        if (imageRequest) {
          visionFactIds.add(factId);
          assertEquals("synthetic-video-vision", body.path("model").asString());
          String url = content.get(1).path("image_url").path("url").asString();
          assertTrue(url.startsWith("data:image/png;base64,"));
          assertArrayEquals(frameBytes, Base64.getDecoder().decode(url.substring(22)));
        } else {
          textFactIds.add(factId);
          assertEquals("synthetic-video-text", body.path("model").asString());
          assertEquals(1, data.path("evidence").size());
          assertEquals(TRANSCRIPT, data.path("evidence").get(0).path("text").asString());
        }
      }
      assertEquals(new HashSet<>(result.factIds()), visionFactIds);
      assertEquals(new HashSet<>(result.factIds()), textFactIds);
    }
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

  private record Request(String path, String authorization, byte[] body) {}

  private static final class MediaModels implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final String key = UUID.randomUUID().toString();

    private MediaModels() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/", this::respond);
      server.start();
    }

    private OpenAiCompatibleModels.Endpoint endpoint(String model) {
      return new OpenAiCompatibleModels.Endpoint(
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), model, key);
    }

    private void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!"POST".equals(exchange.getRequestMethod())
            || body.length > 1_048_576
            || !("Bearer " + key).equals(authorization)) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        requests.add(new Request(path, authorization, body));
        Object response;
        if (path.equals("/v1/audio/transcriptions")) {
          response = Map.of("text", TRANSCRIPT);
        } else if (path.equals("/v1/chat/completions")) {
          var request = JSON.readTree(body);
          var content = request.path("messages").get(1).path("content");
          boolean visual = content.isArray();
          var data =
              JSON.readTree(visual ? content.get(0).path("text").asString() : content.asString());
          Object result;
          if (!data.has("target_fact")) {
            result = Map.of("recall_text", MISLEADING_RECALL);
          } else if (visual) {
            boolean color =
                data.path("target_fact").path("canonical_requirement").asString().contains("颜色");
            if (data.has("claims")) {
              result =
                  Map.of(
                      "complete",
                      color,
                      "support",
                      List.of(Map.of("index", 0, "supported", color)));
            } else {
              result =
                  Map.of("refused", !color, "claims", color ? List.of(VISUAL_CLAIM) : List.of());
            }
          } else {
            boolean wait =
                data.path("target_fact")
                    .path("canonical_requirement")
                    .asString()
                    .contains("重启等待时间");
            result =
                Map.of(
                    "refused",
                    !wait,
                    "quotes",
                    wait
                        ? List.of(
                            Map.of(
                                "evidence_id",
                                data.path("evidence").get(0).path("evidence_id").asString(),
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
