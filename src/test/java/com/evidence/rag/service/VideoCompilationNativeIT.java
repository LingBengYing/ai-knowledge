package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.ProcessVideoDecoder;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real video/native codecs and local ASR/VLM HTTP contracts, not provider quality or RAG proof. */
class VideoCompilationNativeIT {
  @TempDir Path directory;

  @Test
  void nativeVideoOriginalFramesAndEveryAlignedPcmSampleReachStandardModelProtocols()
      throws Exception {
    Path ffmpeg = configuredPath("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VIDEO_DECODER_IT_FFPROBE");
    byte[] source = generate(ffmpeg);
    String key = UUID.randomUUID().toString();
    var requests = new LinkedBlockingQueue<Request>();
    var json = JsonMapper.builder().build();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            String path = exchange.getRequestURI().getPath();
            requests.add(
                new Request(
                    path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestBody().readNBytes(1_048_577)));
            Object content =
                path.endsWith("/audio/transcriptions")
                    ? Map.of("text", "受控音轨转录", "start", 999, "end", 9999)
                    : Map.of(
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
                                    "{\"recall_text\":\"受控画面描述仅用于召回\"}"))));
            byte[] response = json.writeValueAsBytes(content);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    server.start();
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    try (var decoder = new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15), 1);
        var asr =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    new OpenAiCompatibleModels.Endpoint(base, "synthetic-video-asr", key),
                    Duration.ofSeconds(5),
                    65536,
                    true));
        var vision =
            new OpenAiCompatibleVisionModels(
                new OpenAiCompatibleVisionModels.Configuration(
                    new OpenAiCompatibleModels.Endpoint(base, "synthetic-video-vlm", key),
                    Duration.ofSeconds(5),
                    65536,
                    true))) {
      var expected = decoder.decode("synthetic.mp4", "video/mp4", source);
      var compiler =
          new VideoCompilationService(
              decoder,
              new AudioTranscriptionService(asr, 1, Duration.ofSeconds(15)),
              vision,
              Duration.ofSeconds(30));
      var result = compiler.compile("synthetic.mp4", "video/mp4", source, () -> true);
      assertEquals(ModelValues.sha256(source), result.sourceSha256());
      assertEquals(decoder.revision(), result.decoderRevision());
      assertEquals(compiler.revision(), result.compilerRevision());
      assertEquals(2_000_000, result.durationUs());
      assertEquals(0, result.timelineOriginUs());
      assertEquals(
          List.of(0L, 1_000_000L),
          result.frames().stream().map(f -> f.frame().presentationUs()).toList());
      assertEquals(expected.audio().pcm().length / 2, result.audio().sampleCount());
      byte[] pcm = expected.audio().pcm();
      var receivedPcm = new java.io.ByteArrayOutputStream();
      for (int ordinal = 0, offset = 0; offset < pcm.length; ordinal++, offset += 32000) {
        int end = Math.min(offset + 32000, pcm.length);
        var span = result.audio().spans().get(ordinal);
        assertEquals(offset / 32, span.startMs());
        assertEquals((end + 31L) / 32, span.endMs());
        assertEquals("受控音轨转录", span.text());
        var request = requests.remove();
        assertEquals("/v1/audio/transcriptions", request.path());
        assertEquals("Bearer " + key, request.authorization());
        String prefix = "multipart/form-data; boundary=";
        assertTrue(request.contentType().startsWith(prefix));
        String boundary = request.contentType().substring(prefix.length());
        String[] parts =
            new String(request.body(), StandardCharsets.ISO_8859_1)
                .split(Pattern.quote("--" + boundary), -1);
        assertEquals(4, parts.length);
        String file = parts[2];
        byte[] wav =
            file.substring(file.indexOf("\r\n\r\n") + 4, file.length() - 2)
                .getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(AudioPcm.wav(pcm, offset, end), wav);
        receivedPcm.write(wav, 44, wav.length - 44);
      }
      assertArrayEquals(pcm, receivedPcm.toByteArray());
      for (int ordinal = 0; ordinal < result.frames().size(); ordinal++) {
        var frame = result.frames().get(ordinal);
        assertArrayEquals(
            expected.frames().get(ordinal).image().content(), frame.frame().image().content());
        assertEquals("受控画面描述仅用于召回", frame.recall().recallText());
        assertEquals(vision.revision(), frame.recall().modelRevision());
        var request = requests.remove();
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer " + key, request.authorization());
        var body = json.readTree(request.body());
        assertEquals("synthetic-video-vlm", body.path("model").asString());
        var content = body.path("messages").get(1).path("content");
        assertEquals(0, json.readTree(content.get(0).path("text").asString()).size());
        String url = content.get(1).path("image_url").path("url").asString();
        assertTrue(url.startsWith("data:image/png;base64,"));
        assertArrayEquals(
            frame.frame().image().content(), Base64.getDecoder().decode(url.substring(22)));
      }
      assertTrue(requests.isEmpty(), "Only ASR chunks and frame descriptions are requested");
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private byte[] generate(Path ffmpeg) throws Exception {
    Path output = directory.resolve("synthetic.mp4");
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
                "color=c=red:s=96x64:r=4:d=2",
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

  private record Request(String path, String authorization, String contentType, byte[] body) {}
}
