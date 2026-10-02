package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import com.evidence.rag.worker.parser.ProcessImageParser;
import com.evidence.rag.worker.parser.ProcessVideoDecoder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real FFmpeg/Tesseract temporary inputs; ASR and VLM are loopback protocol fixtures, not quality.
 */
class QueryPreparationNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION =
      "  What is the budget?\nWhat approval is required at the end?  ";
  private static final String OCR = "QUERY IMAGE TAIL 731";
  private static final String FIRST_SUBTITLE = "Query subtitle budget marker is 650 USD.";
  private static final String LAST_SUBTITLE = "Final query approval marker is TAIL-917.";
  private static final String CAPTION = "Controlled query image recall, never library evidence.";
  @TempDir Path directory;

  @Test
  void realThreeAttachmentPreparationKeepsCompleteTextOriginalPixelsAndHashOnlyProducts()
      throws Exception {
    Path ffmpeg = configured("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configured("RAG_VIDEO_DECODER_IT_FFPROBE");
    Path tesseract = configured("RAG_IMAGE_OCR_IT_EXECUTABLE");
    String ocrRevision = System.getenv("RAG_IMAGE_OCR_IT_REVISION");
    assertNotNull(ocrRevision, "Explicit native OCR revision is required");
    byte[] png = image();
    byte[] wav = audio(ffmpeg);
    byte[] mp4 = video(ffmpeg);
    var originalFiles = files();
    var attachments =
        List.of(
            new QueryAttachment("query.png", "image/png", png),
            new QueryAttachment("query.wav", "audio/wav", wav),
            new QueryAttachment("query.mp4", "video/mp4", mp4));
    try (var models = new Models();
        var audioDecoder = new ProcessAudioDecoder(ffmpeg, ffprobe, Duration.ofSeconds(20));
        var videoDecoder =
            new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(20), 1, true);
        var ocr =
            new ProcessImageParser(
                new ImageOcrOptions(tesseract, "eng", ocrRevision), Duration.ofSeconds(15));
        var asr =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    new OpenAiCompatibleModels.Endpoint(
                        models.base(), "query-native-asr", models.key),
                    Duration.ofSeconds(5),
                    65536,
                    true));
        var vision =
            new OpenAiCompatibleVisionModels(
                new OpenAiCompatibleVisionModels.Configuration(
                    new OpenAiCompatibleModels.Endpoint(
                        models.base(), "query-native-vlm", models.key),
                    Duration.ofSeconds(5),
                    65536,
                    true))) {
      var decodedAudio = audioDecoder.decode("query.wav", "audio/wav", wav);
      var decodedVideo = videoDecoder.decode("query.mp4", "video/mp4", mp4);
      assertEquals(2, decodedVideo.subtitles().tracks().size());
      assertEquals(4_500_000, decodedVideo.durationUs());
      var subtitles =
          decodedVideo.subtitles().tracks().stream()
              .flatMap(track -> track.cues().stream())
              .filter(cue -> !cue.text().isBlank())
              .map(cue -> cue.text())
              .toList();
      assertEquals(List.of(FIRST_SUBTITLE, "Release requires approval.", LAST_SUBTITLE), subtitles);
      var imageText =
          ocr.read(new VisualImage("image/png", png))
              .orElseThrow()
              .text()
              .pages()
              .getFirst()
              .text();
      assertTrue(
          imageText.contains(OCR), "The PNG text must be recognized by actual English Tesseract");
      assertTrue(models.requests.isEmpty(), "Native inspection itself does not call remote models");
      var audio = new AudioCompilationService(audioDecoder, asr, 1, Duration.ofSeconds(30));
      var video =
          new VideoCompilationService(
              videoDecoder,
              new AudioTranscriptionService(asr, 1, Duration.ofSeconds(30)),
              vision,
              null,
              Duration.ofSeconds(40),
              true);
      var preparation =
          new QueryPreparationService(vision, ocr, audio, video, Duration.ofSeconds(60));
      var prepared = preparation.prepare(QUESTION, attachments, () -> true);
      assertEquals(QUESTION, prepared.originalQuestion());
      assertArrayEquals(
          QUESTION.getBytes(StandardCharsets.UTF_8),
          prepared.originalQuestion().getBytes(StandardCharsets.UTF_8));
      assertTrue(prepared.retrievalText().contains(QUESTION));
      assertTrue(prepared.retrievalText().contains(imageText));
      assertTrue(prepared.retrievalText().contains(CAPTION));
      for (String cue : subtitles) {
        assertTrue(
            prepared.retrievalText().contains(cue),
            "Every original subtitle cue, including the final track tail, reaches recall");
      }
      assertTrue(
          prepared.retrievalText().codePointCount(0, prepared.retrievalText().length()) <= 8192);
      assertEquals(preparation.revision(), prepared.preparationRevision());
      assertTrue(prepared.manifestSha256().matches("[a-f0-9]{64}"));

      var firstRequests = List.copyOf(models.requests);
      var audioRequests =
          firstRequests.stream().filter(r -> r.path().endsWith("/audio/transcriptions")).toList();
      var expectedWavChunks = new ArrayList<byte[]>();
      addWavChunks(expectedWavChunks, decodedAudio.pcm());
      addWavChunks(expectedWavChunks, decodedVideo.audio().pcm());
      assertEquals(expectedWavChunks.size(), audioRequests.size());
      for (int index = 0; index < expectedWavChunks.size(); index++) {
        var request = audioRequests.get(index);
        assertEquals("Bearer " + models.key, request.authorization());
        assertArrayEquals(
            expectedWavChunks.get(index),
            request.wav(),
            "No decoded PCM segment or padded tail may be omitted");
        assertTrue(prepared.retrievalText().contains(request.recallText()));
      }
      var visionRequests =
          firstRequests.stream().filter(r -> r.path().endsWith("/chat/completions")).toList();
      var completeImages = new ArrayList<VisualImage>();
      completeImages.add(new VisualImage("image/png", png));
      decodedVideo.frames().forEach(frame -> completeImages.add(frame.image()));
      assertEquals(completeImages.size(), visionRequests.size());
      for (int index = 0; index < completeImages.size(); index++) {
        var request = visionRequests.get(index);
        assertEquals("Bearer " + models.key, request.authorization());
        var body = JSON.readTree(request.body());
        assertEquals("query-native-vlm", body.path("model").asString());
        String uri =
            body.path("messages")
                .get(1)
                .path("content")
                .get(1)
                .path("image_url")
                .path("url")
                .asString();
        assertTrue(uri.startsWith("data:image/png;base64,"));
        assertArrayEquals(
            completeImages.get(index).content(), Base64.getDecoder().decode(uri.substring(22)));
      }
      var uniqueImages = new LinkedHashMap<String, VisualImage>();
      completeImages.forEach(img -> uniqueImages.putIfAbsent(img.sha256(), img));
      assertTrue(
          uniqueImages.size() > 3, "The dynamic video must exercise explicit visual sampling");
      assertEquals(3, prepared.queryImages().size());
      assertEquals(3, prepared.queryImages().stream().map(VisualImage::sha256).distinct().count());
      assertArrayEquals(png, prepared.queryImages().getFirst().content());
      assertArrayEquals(
          new ArrayList<>(uniqueImages.values()).getLast().content(),
          prepared.queryImages().getLast().content());
      for (var selected : prepared.queryImages()) {
        assertArrayEquals(
            uniqueImages.get(selected.sha256()).content(),
            selected.content(),
            "Query pixels must come from original attachments, never authority");
      }
      assertEquals(3, prepared.attachments().size());
      for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
        var manifest = prepared.attachments().get(ordinal);
        assertEquals(ordinal, manifest.ordinal());
        assertEquals(attachments.get(ordinal).sha256(), manifest.sourceSha256());
        assertEquals(attachments.get(ordinal).kind(), manifest.mediaKind());
        assertTrue(manifest.contentSha256().matches("[a-f0-9]{64}"));
        assertTrue(manifest.textCodePoints() > 0);
      }
      var imageManifest = prepared.attachments().get(0);
      var audioManifest = prepared.attachments().get(1);
      var videoManifest = prepared.attachments().get(2);
      assertEquals(1, imageManifest.visualCount());
      assertEquals(List.of(ModelValues.sha256(png)), imageManifest.selectedImageSha256());
      assertFalse(imageManifest.visualSampled());
      assertEquals(audio.revision(), audioManifest.compilerRevision());
      assertEquals(0, audioManifest.visualCount());
      assertTrue(audioManifest.selectedImageSha256().isEmpty());
      assertEquals(video.revision(), videoManifest.compilerRevision());
      assertEquals(decodedVideo.frames().size(), videoManifest.visualCount());
      assertTrue(videoManifest.visualSampled());
      assertEquals(
          prepared.queryImages().subList(1, 3).stream().map(VisualImage::sha256).toList(),
          videoManifest.selectedImageSha256());
      assertTrue(
          videoManifest.textCodePoints()
              >= subtitles.stream().mapToInt(s -> s.codePointCount(0, s.length())).sum());
      assertFalse(prepared.toString().contains(LAST_SUBTITLE));
      assertFalse(prepared.attachments().toString().contains(imageText));

      models.tail = "CHANGED-ASR-TAIL";
      var changed = preparation.prepare(QUESTION, attachments, () -> true);
      assertEquals(QUESTION, changed.originalQuestion());
      assertEquals(prepared.preparationRevision(), changed.preparationRevision());
      assertEquals(
          prepared.attachments().stream().map(m -> m.sourceSha256()).toList(),
          changed.attachments().stream().map(m -> m.sourceSha256()).toList());
      assertNotEquals(
          audioManifest.contentSha256(),
          changed.attachments().get(1).contentSha256(),
          "Complete ASR content, not just source bytes, must be sealed");
      assertNotEquals(videoManifest.contentSha256(), changed.attachments().get(2).contentSha256());
      assertNotEquals(prepared.manifestSha256(), changed.manifestSha256());
      assertTrue(changed.retrievalText().contains("CHANGED-ASR-TAIL"));
      assertTrue(changed.retrievalText().contains(LAST_SUBTITLE));
      assertEquals(
          originalFiles,
          files(),
          "Preparing attachments must not persist library documents or projection state");
    }
  }

  private static void addWavChunks(List<byte[]> output, byte[] pcm) {
    for (int offset = 0; offset < pcm.length; offset += 32000) {
      output.add(AudioPcm.wav(pcm, offset, Math.min(offset + 32000, pcm.length)));
    }
  }

  private List<Path> files() throws IOException {
    try (var files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).map(directory::relativize).sorted().toList();
    }
  }

  private byte[] image() throws IOException {
    var image = new BufferedImage(1000, 180, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
      graphics.drawString(OCR, 35, 105);
    } finally {
      graphics.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(ImageIO.write(image, "png", output));
    }
    byte[] png = bytes.toByteArray();
    Files.write(directory.resolve("query.png"), png);
    return png;
  }

  private byte[] audio(Path ffmpeg) throws Exception {
    Path output = directory.resolve("query.wav");
    generate(
        List.of(
            ffmpeg.toString(),
            "-hide_banner",
            "-nostdin",
            "-n",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=660:sample_rate=16000:duration=2.25",
            "-c:a",
            "pcm_s16le",
            output.toString()));
    return Files.readAllBytes(output);
  }

  private byte[] video(Path ffmpeg) throws Exception {
    Path first = directory.resolve("first.srt");
    Path second = directory.resolve("second.srt");
    Files.writeString(
        first,
        "1\n00:00:00,500 --> 00:00:01,250\n"
            + FIRST_SUBTITLE
            + "\n\n2\n00:00:02,000 --> 00:00:03,000\nRelease requires approval.\n");
    Files.writeString(second, "1\n00:00:03,500 --> 00:00:04,500\n" + LAST_SUBTITLE + "\n");
    Path output = directory.resolve("query.mp4");
    generate(
        List.of(
            ffmpeg.toString(),
            "-hide_banner",
            "-nostdin",
            "-n",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=160x120:rate=4:duration=4",
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
            "-metadata:s:s:0",
            "language=eng",
            "-metadata:s:s:1",
            "language=eng",
            output.toString()));
    return Files.readAllBytes(output);
  }

  private void generate(List<String> arguments) throws Exception {
    var builder =
        new ProcessBuilder(arguments)
            .directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    Process process = builder.start();
    process.getOutputStream().close();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Native fixture generation deadline");
      assertEquals(0, process.exitValue(), "Native fixture generation exit status");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
      }
    }
  }

  private static Path configured(String name) {
    if (!"true".equals(System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native query attachment opt-in is required");
    }
    String configured = System.getenv(name);
    if (configured == null || configured.isBlank()) {
      throw new IllegalArgumentException("Explicit native binary path is required");
    }
    Path path = Path.of(configured);
    if (!path.isAbsolute() || !Files.isExecutable(path)) {
      throw new IllegalArgumentException("Native binary is unavailable");
    }
    return path;
  }

  private record Request(
      String path, String authorization, String contentType, byte[] body, String recallText) {
    byte[] wav() {
      assertTrue(contentType.startsWith("multipart/form-data; boundary="));
      String boundary = contentType.substring("multipart/form-data; boundary=".length());
      String[] parts =
          new String(body, StandardCharsets.ISO_8859_1).split(Pattern.quote("--" + boundary), -1);
      assertEquals(4, parts.length);
      String file = parts[2];
      return file.substring(file.indexOf("\r\n\r\n") + 4, file.length() - 2)
          .getBytes(StandardCharsets.ISO_8859_1);
    }
  }

  private static final class Models implements AutoCloseable {
    private final String key = UUID.randomUUID().toString();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile String tail = "COMPLETE-ASR-TAIL";

    Models() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/", this::respond);
      server.start();
    }

    URI base() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readNBytes(2_097_153);
        assertTrue(body.length <= 2_097_152);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        var original = new Request(path, authorization, contentType, body, "");
        String recall =
            path.endsWith("/audio/transcriptions")
                ? "Query audio segment "
                    + ModelValues.sha256(original.wav()).substring(0, 12)
                    + " "
                    + tail
                : CAPTION;
        requests.add(new Request(path, authorization, contentType, body, recall));
        Object response;
        if (path.equals("/v1/audio/transcriptions")) {
          response = Map.of("text", recall);
        } else {
          assertEquals("/v1/chat/completions", path);
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
                              "role",
                              "assistant",
                              "content",
                              JSON.writeValueAsString(Map.of("recall_text", recall))))));
        }
        byte[] bytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
