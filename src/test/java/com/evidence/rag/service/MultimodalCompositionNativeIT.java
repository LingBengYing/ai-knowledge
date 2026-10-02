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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Production configuration and native workers; remote models and Milvus are loopback fixtures. */
class MultimodalCompositionNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BUDGET = "Project A's budget is 650 USD.";
  private static final String QUESTION = "What is Project A's budget?";
  private static final String QUERY_OCR = "QUERY ATTACHMENT LIMIT 999 USD";
  private static final String CAPTION = "Synthetic printed presentation on a white background.";
  private static final String TAIL = "Final approval marker is TAIL-917.";
  private static final String ISSUER = "multimodal-composition-test";
  private static final String AUDIENCE = "multimodal-composition-http";
  @TempDir static Path directory;

  @Test
  void productionEnvironmentCompositionUploadsAnswersSummarizesAndRestartsWithoutModelReads()
      throws Exception {
    assertNotNull(directory, "Native HTTP directory must exist before application startup");
    Path ffmpeg = configured("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configured("RAG_VIDEO_DECODER_IT_FFPROBE");
    Path tesseract = configured("RAG_IMAGE_OCR_IT_EXECUTABLE");
    String ocrRevision = System.getenv("RAG_IMAGE_OCR_IT_REVISION");
    assertNotNull(ocrRevision, "Explicit native OCR revision is required");
    byte[] text = BUDGET.getBytes(StandardCharsets.UTF_8);
    byte[] picture = image(QUERY_OCR);
    byte[] audio = audio(ffmpeg);
    byte[] video = video(ffmpeg);
    try (var indexing = new IndexingTestServer();
        var models = new Models();
        var app =
            new Application(
                directory.resolve("authority"),
                ffmpeg,
                ffprobe,
                tesseract,
                ocrRevision,
                indexing,
                models)) {
      assertEquals(
          0, indexing.requests.size(), "Empty startup must not contact vector or embedding");
      assertEquals(0, models.requests.size(), "Empty startup must not contact models");
      app.send("GET", "/v1/management/documents", null, null, null, false, 401);
      app.send("GET", "/health/ready", null, null, null, false, 503);
      var runtime = app.json("GET", "/v1/config", null, null, 200);
      assertEquals("jwt", runtime.path("auth_mode").asString());
      var capabilities = new ArrayList<String>();
      runtime.path("capabilities").forEach(value -> capabilities.add(value.asString()));
      assertTrue(
          capabilities.containsAll(
              List.of(
                  "text_upload",
                  "text_index",
                  "answers",
                  "sources",
                  "visual_image_upload",
                  "visual_answers",
                  "audio_upload",
                  "audio_index",
                  "audio_answers",
                  "video_upload",
                  "video_index",
                  "video_answers",
                  "file_synopsis",
                  "synopsis_sources",
                  "query_attachments")),
          runtime.toString());
      assertFalse(
          capabilities.contains("image_text_upload"), "Visual upload has explicit priority");
      assertNotNull(app.context.getBean(HierarchicalSynopsisService.class));

      var document = app.upload("library.txt", "application/octet-stream", text);
      var imageDocument = app.upload("library.png", "application/octet-stream", picture);
      var audioDocument = app.upload("library.wav", "application/octet-stream", audio);
      var videoDocument = app.upload("library.mp4", "video/mp4", video);
      var store = app.context.getBean(SqliteAuthorityStore.class);
      var repository = app.context.getBean(IngestionRepository.class);
      var imageEvidence =
          store.transaction(
              () -> repository.findImageEvidence(imageDocument.revision()).orElseThrow());
      assertEquals(CAPTION, imageEvidence.recallText());
      var audioCompilation =
          store.transaction(
              () -> repository.findAudioCompilation(audioDocument.revision()).orElseThrow());
      assertEquals(ModelValues.sha256(audio), audioCompilation.sourceSha256());
      assertFalse(audioCompilation.spans().isEmpty());
      var compilation =
          store.transaction(
              () -> repository.findVideoCompilation(videoDocument.revision()).orElseThrow());
      assertEquals(ModelValues.sha256(video), compilation.sourceSha256());
      assertTrue(
          compilation.frames().stream()
                  .map(value -> value.frame().image().sha256())
                  .distinct()
                  .count()
              >= 4,
          "Native video has distinct original frames so query visual sampling is exercised");
      assertNotNull(compilation.audio());
      assertFalse(compilation.audio().spans().isEmpty());
      assertNotNull(compilation.ocr());
      assertEquals(compilation.frames().size(), compilation.ocr().frames().size());
      assertTrue(
          compilation.ocr().frames().stream().allMatch(frame -> frame.text().contains(BUDGET)));
      assertEquals(2, compilation.subtitles().tracks().size());
      var cues =
          compilation.subtitles().tracks().stream()
              .flatMap(track -> track.cues().stream())
              .filter(cue -> !cue.text().isBlank())
              .toList();
      assertEquals(3, cues.size());
      assertEquals(4_500_000, compilation.durationUs());
      var indexedTexts = new ArrayList<String>();
      for (var request : indexing.committedUpserts) {
        for (var row : request.body().path("data")) {
          if (videoDocument.id().equals(row.path("document_id").asString())) {
            indexedTexts.add(row.path("text").asString());
          }
        }
      }
      assertEquals(
          compilation.frames().size()
              + compilation.audio().spans().size()
              + compilation.ocr().frames().stream().mapToInt(frame -> frame.segments().size()).sum()
              + cues.size(),
          indexedTexts.size(),
          "Every original evidence kind is published by the indexing worker");
      assertTrue(indexedTexts.contains(TAIL));

      int modelStart = models.requests.size();
      int retrievalStart = indexing.requests.size();
      var attached =
          app.json(
              "POST",
              "/v1/attachment-answers",
              JSON.writeValueAsBytes(
                  Map.of(
                      "question",
                      QUESTION,
                      "mode",
                      "text",
                      "document_ids",
                      List.of(document.id()),
                      "attachments",
                      List.of(
                          attachment("query.png", "image/png", picture),
                          attachment("query.wav", "audio/wav", audio),
                          attachment("query.mp4", "video/mp4", video)))),
              "application/json",
              200);
      var answer = attached.path("result");
      assertEquals("answered", answer.path("status").asString(), attached.toString());
      assertTrue(answer.path("answer").asString().contains("650"));
      assertFalse(answer.path("answer").asString().contains("999"));
      assertEquals(3, attached.path("query_attachments").size());
      for (var notice : attached.path("query_attachments")) {
        assertEquals("prepared", notice.path("status").asString());
      }
      assertTrue(attached.path("query_attachments").get(2).path("visual_sampled").asBoolean());
      assertFalse(attached.toString().contains(QUERY_OCR));
      assertFalse(attached.toString().contains("query.png"));
      var textCitation = answer.path("citations").get(0);
      assertEquals(document.id(), textCitation.path("document_id").asString());
      assertEquals(ModelValues.sha256(text), textCitation.path("source_sha256").asString());
      String textSource = textCitation.path("source_url").asString();
      var textReadback = app.json("GET", textSource, null, null, 200);
      assertEquals(textCitation, textReadback.path("citation"));

      var retrieval = new StringBuilder();
      for (var request : indexing.requests.subList(retrievalStart, indexing.requests.size())) {
        if (request.path().equals("/embeddings")) {
          request.body().path("input").forEach(value -> retrieval.append(value.asString()));
        }
      }
      assertTrue(retrieval.toString().contains(QUERY_OCR), "Real PNG OCR reaches retrieval");
      assertTrue(
          retrieval.toString().contains(TAIL), "Second subtitle track tail reaches retrieval");
      var queryCalls = List.copyOf(models.requests.subList(modelStart, models.requests.size()));
      var transcriptions = queryCalls.stream().filter(call -> !call.recall().isEmpty()).toList();
      assertTrue(transcriptions.size() >= 7, "Complete WAV and video PCM segments reach ASR");
      for (var call : transcriptions) {
        assertTrue(
            retrieval.toString().contains(call.recall()), "All ASR segments reach retrieval");
      }
      assertTrue(
          queryCalls.stream()
              .anyMatch(
                  call ->
                      call.body() != null
                          && call.body().path("model").asString().equals("composition-ranking")));
      for (var call : queryCalls) {
        if (call.body() != null
            && call.body().path("model").asString().equals("composition-answer")
            && call.path().equals("/v1/chat/completions")) {
          var input = JSON.readTree(call.body().path("messages").get(1).path("content").asString());
          assertEquals(QUESTION, input.path("question").asString());
          assertFalse(input.toString().contains(QUERY_OCR));
          assertFalse(input.toString().contains(TAIL));
          assertEquals(BUDGET, input.path("evidence").get(0).path("text").asString());
        }
      }

      var audioAnswer = app.answer("/v1/audio-answers", audioDocument.id(), null);
      var audioCitation = audioAnswer.path("citations").get(0);
      assertEquals("audio_span", audioCitation.path("kind").asString());
      String audioSource = audioCitation.path("source_url").asString();
      assertEquals(audioCitation, app.json("GET", audioSource, null, null, 200).path("citation"));
      app.sourceBytes(audioSource + "/content", audio);
      var ocrAnswer = app.answer("/v1/video-answers", videoDocument.id(), "ocr");
      var ocrCitation = ocrAnswer.path("citations").get(0);
      assertEquals("video_frame_ocr", ocrCitation.path("kind").asString());
      assertFalse(ocrCitation.path("ocr").path("regions").isEmpty());
      String ocrSource = ocrCitation.path("source_url").asString();
      assertEquals(ocrCitation, app.json("GET", ocrSource, null, null, 200).path("citation"));
      var frame = app.send("GET", ocrSource + "/frame", null, null, null, true, 200).body();
      assertTrue(
          compilation.frames().stream()
              .anyMatch(value -> Arrays.equals(value.frame().image().content(), frame)));
      var subtitleAnswer = app.answer("/v1/video-answers", videoDocument.id(), "subtitle");
      var subtitleCitation = subtitleAnswer.path("citations").get(0);
      assertEquals("video_subtitle", subtitleCitation.path("kind").asString());
      assertEquals("embedded_subtitle", subtitleCitation.path("proof_origin").asString());
      assertEquals("subtitle_cue", subtitleCitation.path("time_precision").asString());
      assertEquals(500000, subtitleCitation.path("start_us").asLong());
      assertEquals(1250000, subtitleCitation.path("end_us").asLong());
      String subtitleSource = subtitleCitation.path("source_url").asString();
      assertEquals(
          subtitleCitation, app.json("GET", subtitleSource, null, null, 200).path("citation"));
      app.sourceBytes(subtitleSource + "/content", video);

      var synopsisTask =
          app.json("POST", "/v1/documents/" + videoDocument.id() + "/synopsis", null, null, 202);
      app.await("synopsis-tasks", synopsisTask.path("task_id").asString(), "available");
      String synopsisPath = "/v1/documents/" + videoDocument.id() + "/synopsis";
      var synopsis = app.json("GET", synopsisPath, null, null, 200);
      String synopsisSource =
          synopsis.path("entries").get(0).path("evidence").get(0).path("source_url").asString();
      var synopsisReadback = app.json("GET", synopsisSource, null, null, 200);
      assertEquals(TAIL, synopsisReadback.path("text").asString());
      assertEquals("video_subtitle", synopsisReadback.path("kind").asString());
      assertEquals(3_500_000, synopsisReadback.path("locator").path("start_us").asLong());
      assertEquals(4_500_000, synopsisReadback.path("locator").path("end_us").asLong());
      assertEquals(cues.stream().map(cue -> cue.text()).toList(), models.synopsisSubtitles);
      app.sourceBytes(synopsisReadback.path("content_url").asString(), video);
      var documents = app.json("GET", "/v1/management/documents", null, null, 200);
      assertEquals(
          4, documents.path("total").asInt(), "Query attachments must not enter the library");
      assertTrue(documents.toString().contains(imageDocument.id()));

      int modelCount = models.requests.size();
      int vectorCount = indexing.requests.size();
      app.restart();
      assertEquals(textReadback, app.json("GET", textSource, null, null, 200));
      assertEquals(audioCitation, app.json("GET", audioSource, null, null, 200).path("citation"));
      assertEquals(ocrCitation, app.json("GET", ocrSource, null, null, 200).path("citation"));
      assertEquals(
          subtitleCitation, app.json("GET", subtitleSource, null, null, 200).path("citation"));
      assertEquals(synopsis, app.json("GET", synopsisPath, null, null, 200));
      assertEquals(synopsisReadback, app.json("GET", synopsisSource, null, null, 200));
      assertArrayEquals(
          frame, app.send("GET", ocrSource + "/frame", null, null, null, true, 200).body());
      app.sourceBytes(audioSource + "/content", audio);
      app.sourceBytes(subtitleSource + "/content", video);
      app.sourceBytes(synopsisReadback.path("content_url").asString(), video);
      assertEquals(
          modelCount, models.requests.size(), "Restart and source reads make zero model requests");
      assertEquals(
          vectorCount,
          indexing.requests.size(),
          "Restart and source reads make zero vector requests");
    }
  }

  private static Map<String, String> attachment(String name, String type, byte[] bytes) {
    return Map.of(
        "filename",
        name,
        "media_type",
        type,
        "content_base64",
        Base64.getEncoder().encodeToString(bytes));
  }

  private static byte[] image(String text) throws IOException {
    return image(text, -1);
  }

  private static byte[] image(String text, int frameOrdinal) throws IOException {
    var pixels = new BufferedImage(1200, 240, BufferedImage.TYPE_INT_RGB);
    var graphics = pixels.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, pixels.getWidth(), pixels.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
      graphics.drawString(text, 35, 140);
      if (frameOrdinal >= 0) {
        graphics.setColor(new Color(30 + frameOrdinal * 20, 80, 180));
        graphics.fillRect(35 + frameOrdinal * 100, 190, 80, 30);
      }
    } finally {
      graphics.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    try (var stream = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(ImageIO.write(pixels, "png", stream));
    }
    return bytes.toByteArray();
  }

  private static byte[] audio(Path ffmpeg) throws Exception {
    Path output = directory.resolve("source.wav");
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

  private static byte[] video(Path ffmpeg) throws Exception {
    Path first = directory.resolve("first.srt");
    Path second = directory.resolve("second.srt");
    for (int ordinal = 0; ordinal < 8; ordinal++) {
      Files.write(directory.resolve("frame-%02d.png".formatted(ordinal)), image(BUDGET, ordinal));
    }
    Files.writeString(
        first,
        "1\n00:00:00,500 --> 00:00:01,250\n"
            + BUDGET
            + "\n\n2\n00:00:02,000 --> 00:00:03,000\nRelease requires approval.\n");
    Files.writeString(second, "1\n00:00:03,500 --> 00:00:04,500\n" + TAIL + "\n");
    Path output = directory.resolve("source.mp4");
    generate(
        List.of(
            ffmpeg.toString(),
            "-hide_banner",
            "-nostdin",
            "-n",
            "-framerate",
            "2",
            "-start_number",
            "0",
            "-i",
            directory.resolve("frame-%02d.png").toString(),
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
            output.toString()));
    return Files.readAllBytes(output);
  }

  private static void generate(List<String> arguments) throws Exception {
    var builder =
        new ProcessBuilder(arguments)
            .directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    var process = builder.start();
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
    assertEquals(
        "true", System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"), "Explicit native opt-in required");
    String value = System.getenv(name);
    assertNotNull(value, "Explicit native binary path is required");
    Path path = Path.of(value);
    assertTrue(path.isAbsolute() && Files.isExecutable(path));
    return path;
  }

  private record Uploaded(String id, String revision) {}

  private static final class Application implements AutoCloseable {
    private final Path data;
    private final Map<String, Object> environment;
    private final String secret = UUID.randomUUID().toString() + UUID.randomUUID();
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private ConfigurableApplicationContext context;
    private String base;

    Application(
        Path data,
        Path ffmpeg,
        Path ffprobe,
        Path tesseract,
        String ocrRevision,
        IndexingTestServer indexing,
        Models models) {
      assertNotNull(data);
      this.data = data;
      environment = new LinkedHashMap<>();
      environment.put("RAG_BIND_ADDRESS", "127.0.0.1");
      environment.put("RAG_PORT", "0");
      environment.put("RAG_ENVIRONMENT", "test");
      environment.put("RAG_WORKSPACE_ID", "org-main");
      environment.put("RAG_AUTH_MODE", "jwt");
      environment.put("RAG_JWT_SECRET", secret);
      environment.put("RAG_JWT_ISSUER", ISSUER);
      environment.put("RAG_JWT_AUDIENCE", AUDIENCE);
      environment.put("RAG_DATA_DIRECTORY", data.toString());
      for (String feature :
          List.of(
              "INGESTION",
              "INDEXING",
              "ANSWERS",
              "IMAGE_OCR",
              "VISUAL",
              "AUDIO",
              "VIDEO",
              "VIDEO_OCR",
              "VIDEO_SUBTITLES",
              "SYNOPSIS",
              "QUERY_ATTACHMENTS")) {
        environment.put("RAG_" + feature + "_ENABLED", "true");
      }
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        environment.put(
            "RAG_" + kind + "_BASE_URL",
            kind.equals("EMBEDDING") ? indexing.endpoint().toString() : models.endpoint());
        environment.put(
            "RAG_" + kind + "_MODEL",
            kind.equals("EMBEDDING") ? "fixture-model" : "composition-answer");
        environment.put("RAG_" + kind + "_API_KEY", models.key);
      }
      environment.put("RAG_EMBEDDING_DIMENSIONS", "2");
      environment.put("RAG_EMBEDDING_REVISION", "fixture-v1");
      environment.put("RAG_MILVUS_ENDPOINT", indexing.endpoint().toString());
      environment.put("RAG_MILVUS_TOKEN", models.key);
      environment.put("RAG_MILVUS_COLLECTION", indexing.settings().projection().collection());
      environment.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
      environment.put("RAG_TEXT_DEADLINE_MS", "10000");
      environment.put("RAG_ANSWERS_TIMEOUT_MS", "60000");
      environment.put("RAG_INGESTION_PARSE_TIMEOUT_MS", "60000");
      environment.put("RAG_INDEXING_TIMEOUT_MS", "60000");
      for (String kind : List.of("IMAGE_OCR", "VIDEO_OCR")) {
        environment.put("RAG_" + kind + "_EXECUTABLE", tesseract.toString());
        environment.put("RAG_" + kind + "_LANGUAGE", "eng");
        environment.put("RAG_" + kind + "_REVISION", ocrRevision);
      }
      for (String kind : List.of("AUDIO", "VIDEO")) {
        environment.put("RAG_" + kind + "_FFMPEG_EXECUTABLE", ffmpeg.toString());
        environment.put("RAG_" + kind + "_FFPROBE_EXECUTABLE", ffprobe.toString());
        environment.put("RAG_" + kind + "_CHUNK_SECONDS", "1");
        environment.put("RAG_" + kind + "_DECODE_DEADLINE_MS", "20000");
        environment.put("RAG_" + kind + "_COMPILATION_BUDGET_MS", "60000");
      }
      environment.put("RAG_VIDEO_FRAME_INTERVAL_SECONDS", "1");
      environment.put("RAG_SYNOPSIS_BUDGET_MS", "30000");
      for (String kind :
          List.of("VISION", "AUDIO", "VIDEO_ASR", "VIDEO_VISION", "SYNOPSIS", "QUERY_RANKING")) {
        environment.put("RAG_" + kind + "_BASE_URL", models.endpoint());
        environment.put(
            "RAG_" + kind + "_MODEL",
            kind.equals("SYNOPSIS")
                ? "composition-synopsis"
                : kind.equals("QUERY_RANKING") ? "composition-ranking" : "composition-media");
        environment.put("RAG_" + kind + "_API_KEY", models.key);
        environment.put("RAG_" + kind + "_ALLOW_LOOPBACK_HTTP", "true");
        environment.put("RAG_" + kind + "_DEADLINE_MS", "10000");
      }
      start();
    }

    private void start() {
      assertNotNull(data, "Data directory must exist before Spring configuration binding");
      var springEnvironment = new StandardEnvironment();
      springEnvironment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      springEnvironment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      springEnvironment
          .getPropertySources()
          .addFirst(
              new SystemEnvironmentPropertySource(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
      var application = new SpringApplication(RagApplication.class);
      application.setEnvironment(springEnvironment);
      context = application.run();
      assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
      base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    void restart() {
      context.close();
      start();
    }

    Uploaded upload(String name, String type, byte[] bytes) throws Exception {
      var upload = json("POST", "/v1/documents?filename=" + name, bytes, type, 202);
      await("ingestions", upload.path("task_id").asString(), "parsed");
      String document = upload.path("document_id").asString();
      var index = json("POST", "/v1/documents/" + document + "/index", null, null, 202);
      await("indexings", index.path("task_id").asString(), "indexed");
      return new Uploaded(document, upload.path("revision_id").asString());
    }

    JsonNode answer(String path, String document, String mode) throws Exception {
      var input = new LinkedHashMap<String, Object>();
      input.put("question", QUESTION);
      input.put("document_ids", List.of(document));
      if (mode != null) {
        input.put("mode", mode);
      }
      var result = json("POST", path, JSON.writeValueAsBytes(input), "application/json", 200);
      assertEquals("answered", result.path("status").asString(), result.toString());
      assertTrue(result.path("answer").asString().contains("650"));
      assertFalse(result.path("citations").isEmpty());
      return result;
    }

    JsonNode json(String method, String path, byte[] body, String type, int status)
        throws Exception {
      return JSON.readTree(send(method, path, body, type, null, true, status).body());
    }

    HttpResponse<byte[]> send(
        String method,
        String path,
        byte[] body,
        String type,
        String range,
        boolean authenticated,
        int status)
        throws Exception {
      var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(70));
      if (authenticated) {
        Instant now = Instant.now();
        var claims =
            new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("owner")
                .claim("workspace_id", "org-main")
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(120)))
                .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(secret));
        request.header("Authorization", "Bearer " + token.serialize());
      }
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
      assertArrayEquals(original, send("GET", path, null, null, null, true, 200).body());
      var range = send("GET", path, null, null, "bytes=1-4", true, 206);
      assertArrayEquals(Arrays.copyOfRange(original, 1, 5), range.body());
      assertEquals(
          "bytes 1-4/" + original.length,
          range.headers().firstValue("Content-Range").orElseThrow());
    }

    void await(String kind, String id, String expected) throws Exception {
      long end = System.nanoTime() + Duration.ofSeconds(75).toNanos();
      while (System.nanoTime() < end) {
        var result = json("GET", "/v1/" + kind + "/" + id, null, null, 200);
        if (!List.of("queued", "processing").contains(result.path("state").asString())) {
          assertEquals(expected, result.path("state").asString(), result.toString());
          return;
        }
        Thread.sleep(40);
      }
      fail("Synthetic multimodal job did not complete before its deadline");
    }

    @Override
    public void close() {
      if (context != null) {
        context.close();
      }
      client.close();
    }
  }

  private record Request(String path, JsonNode body, String recall) {}

  private static final class Models implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String key = UUID.randomUUID().toString();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> synopsisSubtitles = new CopyOnWriteArrayList<>();

    Models() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/", this::respond);
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        byte[] bytes = exchange.getRequestBody().readNBytes(4_194_305);
        if (bytes.length > 4_194_304
            || !("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        String path = exchange.getRequestURI().getPath();
        Object response;
        if (path.equals("/v1/audio/transcriptions")) {
          String recall =
              BUDGET + " Audio marker " + ModelValues.sha256(bytes).substring(0, 12) + ".";
          requests.add(new Request(path, null, recall));
          response = Map.of("text", recall);
        } else {
          var body = JSON.readTree(bytes);
          requests.add(new Request(path, body, ""));
          if (path.equals("/v1/rerank")) {
            var ranks = new ArrayList<Object>();
            for (int i = 0; i < body.path("documents").size(); i++) {
              ranks.add(Map.of("index", i, "relevance_score", 100.0 - i));
            }
            response = Map.of("results", ranks);
          } else if (path.equals("/v1/chat/completions")) {
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
                                JSON.writeValueAsString(completion(body))))));
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

    private Object completion(JsonNode body) {
      var content = body.path("messages").get(1).path("content");
      var input =
          JSON.readTree(
              content.isArray() ? content.get(0).path("text").asString() : content.asString());
      String model = body.path("model").asString();
      if (model.equals("composition-ranking")) {
        var ranks = new ArrayList<Map<String, Object>>();
        for (var item : content) {
          if (item.path("type").asString().equals("text")) {
            var marker = JSON.readTree(item.path("text").asString());
            if (marker.path("role").asString().equals("authorized_candidate")) {
              ranks.add(Map.of("index", marker.path("index").asInt(), "score", 0.99));
            }
          }
        }
        return Map.of("rankings", ranks);
      }
      if (model.equals("composition-synopsis")) {
        var ids = new ArrayList<String>();
        for (var source : input.path("evidence")) {
          if (source.path("text").asString().equals(TAIL)) {
            ids.add(source.path("evidence_id").asString());
          }
          if (!input.has("statement") && source.path("kind").asString().equals("video_subtitle")) {
            synopsisSubtitles.add(source.path("text").asString());
          }
        }
        if (input.has("statement")) {
          return Map.of(
              "supported",
              input.path("statement").asString().equals(TAIL) && ids.size() == 1,
              "contributing_evidence_ids",
              ids);
        }
        var items =
            List.of("overview", "topic", "term", "timeline").stream()
                .map(section -> Map.of("section", section, "text", TAIL, "evidence_ids", ids))
                .toList();
        return Map.of("refused", false, "items", items);
      }
      if (content.isArray()) {
        return Map.of("recall_text", CAPTION);
      }
      var quotes = new ArrayList<Map<String, Object>>();
      for (var source : input.path("evidence")) {
        if (source.path("text").asString().contains(BUDGET)) {
          quotes.add(Map.of("evidence_id", source.path("evidence_id").asString(), "quote", BUDGET));
        }
      }
      return Map.of("refused", quotes.isEmpty(), "quotes", quotes);
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
