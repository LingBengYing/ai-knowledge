package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Spring HTTP, SQLite, FFmpeg and worker processes; synthetic loopback model judgments only.
 */
class SoundLibraryMainlineNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION = "完整片段中听到了什么声音？";
  private static final String HALF_QUESTION = "低音和高音是否在同一完整片段中同时出现？";
  private static final String CONFLICT_QUESTION = "这些片段中的声音属于哪一种？";
  private static final String FACT = "该片段包含持续的低音。";
  private static final String OTHER_FACT = "该片段包含持续的高音。";
  private static final String RECALL = "合成声音片段，仅用于召回。";
  private static final String COLLECTION = "java_sound_native_fixture";
  private static final String POLICY = "java-sound-answer-v1";
  @TempDir Path directory;

  @Test
  void pureSoundPublishesAllPcmProvesWholeQuestionsAndReopensOriginalSourcesWithoutModels()
      throws Exception {
    assertEquals("true", System.getenv("RAG_SOUND_IT_ENABLED"));
    byte[] oldPcm = pcm(880, 0, 440), newPcm = pcm(880, 0, 880), quietPcm = pcm(0, 0, 0);
    byte[] oldAudio = AudioPcm.wav(oldPcm, 0, oldPcm.length);
    byte[] newAudio = AudioPcm.wav(newPcm, 0, newPcm.length);
    byte[] quietAudio = AudioPcm.wav(quietPcm, 0, quietPcm.length);
    Path data = directory.resolve("data");
    String sourceUrl, contentUrl, oldDocument;
    JsonNode citation;
    try (var remote = new Providers();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      try (var app = application(remote, data)) {
        assertFalse(app.containsBean("audioModels"), "ASR is not configured for the sound library");
        assertFalse(app.containsBean("audioCompilationService"));
        assertTrue(remote.requests.isEmpty(), "Startup does not call models or projections");
        String base = base(app);
        var uploaded = upload(http, base, "owner", "old-sound.wav", oldAudio);
        oldDocument = uploaded.path("document_id").asString();
        String newDocument =
            upload(http, base, "owner", "new-sound.wav", newAudio).path("document_id").asString();
        String privateDocument =
            upload(http, base, "other-owner", "private-sound.wav", oldAudio)
                .path("document_id")
                .asString();
        assertEquals(4, uploaded.size());
        assertEquals(ModelValues.sha256(oldAudio), uploaded.path("source_sha256").asString());
        assertEquals(oldAudio.length, uploaded.path("size_bytes").asInt());
        assertTrue(
            remote.requests.isEmpty(), "Raw non-speech uploads call no ASR, model or projection");
        var registered = row(http, base, oldDocument);
        assertEquals("ready", registered.path("status").asString());
        assertEquals("not_indexed", registered.path("index_status").asString());
        assertFalse(registered.path("synthetic_fixture").asBoolean());
        assertFalse(registered.path("can_index").asBoolean());
        assertTrue(registered.path("active_revision_id").isNull());
        assertEquals(
            uploaded.path("source_revision_id"), registered.path("registered_revision_id"));
        assertTrue(registered.path("latest_job").isNull());
        assertTrue(registered.path("latest_index_job").isNull());
        var missing = json(http, base, "owner", "GET", indexPath(oldDocument), null, null, 200);
        assertEquals("missing", missing.path("status").asString());
        assertEquals(12, missing.size());
        assertTrue(missing.path("publication_id").isNull());
        assertTrue(remote.requests.isEmpty(), "Metadata GET does not decode or use providers");
        json(http, base, "owner", "GET", indexPath(privateDocument), null, null, 404);
        var privateQuery = Map.of("question", QUESTION, "document_ids", List.of(privateDocument));
        json(
            http,
            base,
            "owner",
            "POST",
            "/v1/sound-answers",
            JSON.writeValueAsBytes(privateQuery),
            "application/json",
            404);
        assertTrue(remote.requests.isEmpty(), "Unauthorized scope is rejected before providers");

        var oldIndex = json(http, base, "owner", "POST", indexPath(oldDocument), null, null, 200);
        assertEquals("available", oldIndex.path("status").asString());
        assertEquals(
            3, oldIndex.path("span_count").asInt(), "Silent middle window is published too");
        remote.assertHealthy();
        assertEquals(3, remote.describeWavs.size());
        assertEquals(3, remote.audioWavs.size());
        int beforeMissing = remote.requests.size();
        var unavailable =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/sound-query-answers",
                attached(QUESTION, List.of(oldDocument, newDocument), quietAudio),
                "application/json",
                409);
        assertEquals("sound_index_required", unavailable.path("error_code").asString());
        assertEquals(
            beforeMissing,
            remote.requests.size(),
            "One missing selected publication blocks all provider dispatch");
        var newIndex = json(http, base, "owner", "POST", indexPath(newDocument), null, null, 200);
        assertEquals("available", newIndex.path("status").asString());
        assertEquals(3, newIndex.path("span_count").asInt());
        assertNotEquals(oldIndex.path("generation_id"), newIndex.path("generation_id"));
        assertEquals(6, remote.describeWavs.size());
        assertEquals(6, remote.audioWavs.size());
        var expectedBuild =
            List.of(
                AudioPcm.wav(oldPcm, 0, 32000),
                AudioPcm.wav(oldPcm, 32000, 64000),
                AudioPcm.wav(oldPcm, 64000, 96000),
                AudioPcm.wav(newPcm, 0, 32000),
                AudioPcm.wav(newPcm, 32000, 64000),
                AudioPcm.wav(newPcm, 64000, 96000));
        for (int i = 0; i < expectedBuild.size(); i++) {
          assertArrayEquals(expectedBuild.get(i), remote.describeWavs.get(i));
          assertArrayEquals(expectedBuild.get(i), remote.audioWavs.get(i));
        }
        assertEquals(6, remote.rows.get(COLLECTION).size());
        var expectedPcmShas =
            new HashSet<>(
                expectedBuild.stream()
                    .map(wav -> ModelValues.sha256(Arrays.copyOfRange(wav, 44, wav.length)))
                    .toList());
        for (var saved : remote.rows.get(COLLECTION).values()) {
          assertTrue(
              expectedPcmShas.contains(saved.path("text").asString()),
              "Projection text carries only the actual PCM SHA, never the common recall description");
        }
        int beforeIdempotent = remote.requests.size();
        assertEquals(
            oldIndex, json(http, base, "owner", "GET", indexPath(oldDocument), null, null, 200));
        assertEquals(
            oldIndex, json(http, base, "owner", "POST", indexPath(oldDocument), null, null, 200));
        assertEquals(
            beforeIdempotent,
            remote.requests.size(),
            "GET and idempotent build use the saved receipt");
        var published = row(http, base, oldDocument);
        assertEquals("parsed", published.path("status").asString());
        assertEquals("indexed", published.path("index_status").asString());
        assertEquals(uploaded.path("source_revision_id"), published.path("active_revision_id"));
        assertFalse(published.path("synthetic_fixture").asBoolean());
        assertFalse(published.path("can_index").asBoolean());
        assertTrue(published.path("latest_job").isNull());
        assertTrue(published.path("latest_index_job").isNull());

        var textAnswer =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/sound-answers",
                JSON.writeValueAsBytes(Map.of("question", QUESTION)),
                "application/json",
                200);
        assertEquals(6, textAnswer.size());
        assertAnswered(textAnswer, oldDocument);
        assertEquals(1, remote.textQuestions.size());
        assertEquals(1, remote.drafts.size());
        assertEquals(1, remote.verifications.size());
        assertArrayEquals(expectedBuild.get(2), remote.drafts.getFirst().wav());
        assertArrayEquals(expectedBuild.get(2), remote.verifications.getFirst().wav());
        assertEquals(QUESTION, remote.textQuestions.getFirst());
        assertEquals(QUESTION, remote.drafts.getFirst().input().path("question").asString());
        assertEquals(
            FACT,
            remote.verifications.getFirst().input().path("claims").get(0).path("claim").asString());

        var attachedAnswer =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/sound-query-answers",
                attached(QUESTION, null, quietAudio),
                "application/json",
                200);
        assertEquals(8, attachedAnswer.size());
        assertEquals("SOUND", attachedAnswer.path("mode").asString());
        assertAnswered(attachedAnswer, oldDocument);
        assertEquals(1, attachedAnswer.path("attachment_manifest").size());
        var manifest = attachedAnswer.path("attachment_manifest").get(0);
        assertEquals(9, manifest.size());
        assertEquals(0, manifest.path("ordinal").asInt());
        assertEquals(ModelValues.sha256(quietAudio), manifest.path("source_sha256").asString());
        assertEquals("audio", manifest.path("media_kind").asString());
        assertEquals(0, manifest.path("text_code_points").asInt());
        assertEquals(0, manifest.path("visual_count").asInt());
        assertTrue(manifest.path("selected_image_sha256").isEmpty());
        assertFalse(manifest.path("visual_sampled").asBoolean());
        assertTrue(manifest.path("content_sha256").asString().matches("[0-9a-f]{64}"));
        assertEquals(
            9, remote.audioWavs.size(), "All three fully silent query windows are embedded");
        for (int i = 6; i < 9; i++) {
          assertArrayEquals(
              AudioPcm.wav(quietPcm, (i - 6) * 32000, (i - 5) * 32000), remote.audioWavs.get(i));
        }
        assertEquals(
            6,
            remote.describeWavs.size(),
            "Query preparation never describes or transcribes query audio");
        assertEquals(4, remote.drafts.size());
        assertEquals(2, remote.verifications.size());
        citation = attachedAnswer.path("citations").get(0);
        assertEquals(23, citation.size());
        assertEquals("sound_span", citation.path("kind").asString());
        assertEquals(uploaded.path("source_revision_id"), citation.path("revision_id"));
        assertEquals(ModelValues.sha256(oldAudio), citation.path("source_sha256").asString());
        assertEquals(oldIndex.path("publication_id"), citation.path("publication_id"));
        assertEquals(oldIndex.path("profile_fingerprint"), citation.path("profile_fingerprint"));
        assertEquals("old-sound.wav", citation.path("filename").asString());
        assertEquals("audio/wav", citation.path("media_type").asString());
        assertEquals(32000, citation.path("start_sample").asLong());
        assertEquals(48000, citation.path("end_sample").asLong());
        assertEquals(16000, citation.path("sample_rate").asInt());
        assertEquals(2000, citation.path("start_ms").asLong());
        assertEquals(3000, citation.path("end_ms").asLong());
        assertEquals(
            ModelValues.sha256(Arrays.copyOfRange(oldPcm, 64000, 96000)),
            citation.path("pcm_sha256").asString());
        assertEquals("server_window", citation.path("time_precision").asString());
        assertEquals(FACT, citation.path("facts").get(0).asString());
        assertEquals(
            ModelValues.sha256(JSON.writeValueAsBytes(List.of(FACT))),
            citation.path("facts_sha256").asString());
        assertEquals(oldIndex.path("model_revision"), citation.path("analysis_model_revision"));
        assertEquals(POLICY, citation.path("policy_revision").asString());
        assertFalse(citation.has("quote") || citation.has("text_origin"));
        sourceUrl = citation.path("source_url").asString();
        contentUrl = citation.path("content_url").asString();
        assertTrue(sourceUrl.startsWith("/v1/sound-sources/"));
        assertEquals(sourceUrl + "/content", contentUrl);
        int beforeSources = remote.requests.size();
        assertEquals(
            citation,
            json(http, base, "owner", "GET", sourceUrl, null, null, 200).path("citation"));
        assertOriginal(http, base, contentUrl, oldAudio);
        json(http, base, "other-owner", "GET", sourceUrl, null, null, 404);
        assertEquals(beforeSources, remote.requests.size(), "Sources never invoke models");

        var half =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/sound-answers",
                JSON.writeValueAsBytes(Map.of("question", HALF_QUESTION)),
                "application/json",
                200);
        assertAbstained(half, "incomplete_evidence");
        assertEquals(8, remote.drafts.size());
        assertEquals(
            2,
            remote.verifications.size(),
            "Partial windows cannot be assembled into a complete answer");
        var conflict =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/sound-answers",
                JSON.writeValueAsBytes(Map.of("question", CONFLICT_QUESTION)),
                "application/json",
                200);
        assertAbstained(conflict, "conflicting_evidence");
        assertEquals(12, remote.drafts.size());
        assertEquals(
            6,
            remote.verifications.size(),
            "Every complete candidate receives independent verification before conflict rejection");
        assertEquals(
            List.of(QUESTION, QUESTION, HALF_QUESTION, CONFLICT_QUESTION), remote.textQuestions);
        var searches =
            remote.requests.stream()
                .filter(call -> call.path().endsWith("entities/search"))
                .toList();
        assertEquals(7, searches.size());
        for (var search : searches) {
          assertEquals("dense", search.body().path("annsField").asString());
          String filter = search.body().path("filter").asString();
          assertTrue(filter.contains(oldDocument) && filter.contains(newDocument));
          assertTrue(filter.contains(oldIndex.path("generation_id").asString()));
          assertTrue(filter.contains(newIndex.path("generation_id").asString()));
          assertFalse(filter.contains(privateDocument));
        }
        remote.assertHealthy();
        assertTrue(
            remote.requests.stream()
                .noneMatch(
                    call ->
                        call.path().contains("transcriptions")
                            || call.path().contains("hybrid_search")));
      }
      int beforeRestart = remote.requests.size();
      try (var app = application(remote, data)) {
        String base = base(app);
        assertEquals(
            citation,
            json(http, base, "owner", "GET", sourceUrl, null, null, 200).path("citation"));
        assertOriginal(http, base, contentUrl, oldAudio);
        assertEquals(
            "available",
            json(http, base, "owner", "GET", indexPath(oldDocument), null, null, 200)
                .path("status")
                .asString());
        assertEquals(
            beforeRestart,
            remote.requests.size(),
            "Restart and saved source/index reads are model-free");
        remote.assertHealthy();
      }
    }
  }

  private static void assertAnswered(JsonNode answer, String document) {
    assertEquals("answered", answer.path("status").asString(), answer.toString());
    assertEquals(FACT, answer.path("answer").asString());
    assertTrue(answer.path("reason_code").isNull());
    assertEquals(POLICY, answer.path("policy_revision").asString());
    assertEquals(1, answer.path("citations").size());
    assertEquals(document, answer.path("citations").get(0).path("document_id").asString());
  }

  private static void assertAbstained(JsonNode answer, String reason) {
    assertEquals("abstained", answer.path("status").asString(), answer.toString());
    assertEquals(reason, answer.path("reason_code").asString());
    assertTrue(answer.path("citations").isEmpty());
    assertFalse(answer.path("answer_id").asString().isBlank());
  }

  private ConfigurableApplicationContext application(Providers remote, Path data) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    Path ffmpeg = nativePath("RAG_VIDEO_DECODER_IT_FFMPEG"),
        ffprobe = nativePath("RAG_VIDEO_DECODER_IT_FFPROBE");
    String decoderRevision;
    try (var decoder = new ProcessAudioDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15))) {
      decoderRevision = decoder.revision();
    }
    return application.run(
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--rag.environment=test",
        "--rag.workspace-id=org-main",
        "--rag.auth-mode=development_headers",
        "--rag.data-directory=" + data,
        "--rag.ingestion.enabled=true",
        "--rag.indexing.enabled=false",
        "--rag.answers.enabled=false",
        "--rag.visual.enabled=false",
        "--rag.audio.enabled=false",
        "--rag.video.enabled=false",
        "--rag.query-attachments.enabled=false",
        "--rag.voice-questions.enabled=false",
        "--rag.image-ocr.enabled=false",
        "--rag.pdf-ocr.enabled=false",
        "--rag.image-embedding.enabled=false",
        "--rag.audio-embedding.enabled=false",
        "--rag.sound.enabled=true",
        "--rag.sound.ffmpeg-executable=" + ffmpeg,
        "--rag.sound.ffprobe-executable=" + ffprobe,
        "--rag.sound.decoder-revision=" + decoderRevision,
        "--rag.sound.chunk-seconds=1",
        "--rag.sound.decode-deadline-ms=15000",
        "--rag.sound.processing-timeout-ms=120000",
        "--rag.sound.max-concurrent=2",
        "--rag.sound.base-url=" + remote.endpoint(),
        "--rag.sound.model=fixture-sound",
        "--rag.sound.api-key=synthetic-fixture-credential",
        "--rag.sound.revision=fixture-sound-v1",
        "--rag.sound.allow-loopback-http=true",
        "--rag.sound.embedding.base-url=" + remote.endpoint(),
        "--rag.sound.embedding.model=fixture-sound-embedding",
        "--rag.sound.embedding.api-key=synthetic-fixture-credential",
        "--rag.sound.embedding.revision=fixture-sound-embedding-v1",
        "--rag.sound.embedding.dimensions=2",
        "--rag.sound.embedding.allow-loopback-http=true",
        "--rag.sound.milvus.endpoint=" + remote.endpoint(),
        "--rag.sound.milvus.token=synthetic-fixture-credential",
        "--rag.sound.milvus.collection=" + COLLECTION,
        "--rag.sound.milvus.allow-loopback-http=true");
  }

  private static byte[] pcm(int... frequencies) {
    var samples = ByteBuffer.allocate(frequencies.length * 32000).order(ByteOrder.LITTLE_ENDIAN);
    for (int frequency : frequencies) {
      for (int sample = 0; sample < 16000; sample++) {
        // Actual audible non-speech tones; 0 Hz produces an entirely silent window.
        samples.putShort(
            (short) Math.round(12000 * Math.sin(2 * Math.PI * frequency * sample / 16000.0)));
      }
    }
    return samples.array();
  }

  private static Path nativePath(String name) {
    String value = System.getenv(name);
    assertNotNull(value, "Native path must be explicitly supplied");
    Path path = Path.of(value);
    assertTrue(path.isAbsolute() && Files.isExecutable(path));
    return path;
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
  }

  private static String indexPath(String document) {
    return "/v1/documents/" + document + "/sound-index";
  }

  private static byte[] attached(String question, List<String> documents, byte[] audio)
      throws Exception {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("question", question);
    fields.put("mode", "SOUND");
    if (documents != null) {
      fields.put("document_ids", documents);
    }
    fields.put(
        "attachments",
        List.of(
            Map.of(
                "filename",
                "quiet-query.wav",
                "media_type",
                "audio/wav",
                "content_base64",
                Base64.getEncoder().encodeToString(audio))));
    return JSON.writeValueAsBytes(fields);
  }

  private static JsonNode upload(
      HttpClient http, String base, String actor, String filename, byte[] audio) throws Exception {
    var response =
        http.send(
            builder(base, actor, "/v1/sound-documents")
                .header("X-Filename", filename)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private static JsonNode row(HttpClient http, String base, String document) throws Exception {
    for (var item :
        json(http, base, "owner", "GET", "/v1/management/documents", null, null, 200)
            .path("items")) {
      if (document.equals(item.path("document_id").asString())) {
        return item;
      }
    }
    throw new AssertionError("A genuine sound document must be visible in management");
  }

  private static void assertOriginal(
      HttpClient http, String base, String contentUrl, byte[] original) throws Exception {
    var response = request(http, base, "owner", "GET", contentUrl, null, null);
    assertEquals(200, response.statusCode());
    assertArrayEquals(original, response.body());
    assertEquals(ModelValues.sha256(original), ModelValues.sha256(response.body()));
    assertEquals("audio/wav", response.headers().firstValue("Content-Type").orElseThrow());
    var ranged =
        http.send(
            builder(base, "owner", contentUrl).header("Range", "bytes=64044-64075").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(206, ranged.statusCode());
    assertEquals(
        "bytes 64044-64075/" + original.length,
        ranged.headers().firstValue("Content-Range").orElseThrow());
    assertArrayEquals(Arrays.copyOfRange(original, 64044, 64076), ranged.body());
  }

  private static JsonNode json(
      HttpClient http,
      String base,
      String actor,
      String method,
      String path,
      byte[] body,
      String type,
      int expected)
      throws Exception {
    var response = request(http, base, actor, method, path, body, type);
    assertEquals(
        expected, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private static HttpRequest.Builder builder(String base, String actor, String path) {
    return HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(130))
        .header("Origin", base)
        .header("X-Workspace-Id", "org-main")
        .header("X-Principal-Id", actor);
  }

  private static HttpResponse<byte[]> request(
      HttpClient http,
      String base,
      String actor,
      String method,
      String path,
      byte[] body,
      String type)
      throws Exception {
    var request = builder(base, actor, path);
    if (type != null) {
      request.header("Content-Type", type);
    }
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static final class Providers implements AutoCloseable {
    private static final Pattern SCOPE =
        Pattern.compile(
            "\\(document_id == \"([A-Za-z0-9._:-]+)\" && revision_id == \"([A-Za-z0-9._:-]+)\"\\)");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();
    private final Map<String, Map<String, JsonNode>> rows = new ConcurrentHashMap<>();
    private final List<Call> requests = new CopyOnWriteArrayList<>();
    private final List<byte[]> audioWavs = new CopyOnWriteArrayList<>();
    private final List<byte[]> describeWavs = new CopyOnWriteArrayList<>();
    private final List<String> textQuestions = new CopyOnWriteArrayList<>();
    private final List<Assessment> drafts = new CopyOnWriteArrayList<>();
    private final List<Assessment> verifications = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    private record Call(String path, JsonNode body) {}

    private record Assessment(JsonNode input, byte[] wav) {}

    Providers() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void assertHealthy() {
      assertTrue(failures.isEmpty(), failures.toString());
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        try {
          assertEquals("POST", exchange.getRequestMethod());
          assertEquals(null, exchange.getRequestURI().getRawQuery());
          String path = exchange.getRequestURI().getPath();
          JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
          requests.add(new Call(path, body));
          Object response;
          if (path.equals("/v1beta/models/fixture-sound-embedding:embedContent")) {
            googleHeaders(exchange);
            response = embed(body);
          } else if (path.equals("/v1beta/interactions")) {
            googleHeaders(exchange);
            response = interaction(body);
          } else {
            assertTrue(
                path.startsWith("/v2/vectordb/"),
                "No ASR, chat or unknown provider route is allowed: " + path);
            assertEquals(
                "Bearer synthetic-fixture-credential",
                exchange.getRequestHeaders().getFirst("Authorization"));
            response = Map.of("code", 0, "data", milvus(path, body));
          }
          byte[] responseBytes = JSON.writeValueAsBytes(response);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBytes.length);
          exchange.getResponseBody().write(responseBytes);
        } catch (Throwable invalid) {
          failures.add(invalid);
          exchange.sendResponseHeaders(500, -1);
        }
      }
    }

    private static void googleHeaders(HttpExchange exchange) {
      assertEquals(
          "synthetic-fixture-credential", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
      assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private Object embed(JsonNode body) {
      assertEquals(Set.of("content", "embedContentConfig"), new HashSet<>(body.propertyNames()));
      assertEquals(Set.of("parts"), new HashSet<>(body.path("content").propertyNames()));
      assertEquals(1, body.path("content").path("parts").size());
      assertEquals(
          Set.of("outputDimensionality", "autoTruncate"),
          new HashSet<>(body.path("embedContentConfig").propertyNames()));
      assertEquals(2, body.path("embedContentConfig").path("outputDimensionality").asInt());
      assertFalse(body.path("embedContentConfig").path("autoTruncate").asBoolean());
      var part = body.path("content").path("parts").get(0);
      List<Double> vector;
      if (part.has("text")) {
        assertEquals(Set.of("text"), new HashSet<>(part.propertyNames()));
        String question = part.path("text").asString();
        assertTrue(List.of(QUESTION, HALF_QUESTION, CONFLICT_QUESTION).contains(question));
        textQuestions.add(question);
        vector =
            question.equals(QUESTION) ? List.of(1.0, 0.0) : List.of(Math.sqrt(0.5), Math.sqrt(0.5));
      } else {
        assertEquals(Set.of("inlineData"), new HashSet<>(part.propertyNames()));
        var audio = part.path("inlineData");
        assertEquals(Set.of("mimeType", "data"), new HashSet<>(audio.propertyNames()));
        assertEquals("audio/wav", audio.path("mimeType").asString());
        byte[] wav = Base64.getDecoder().decode(audio.path("data").asString());
        audioWavs.add(wav);
        vector = vector(wav);
      }
      return Map.of("embedding", Map.of("values", vector));
    }

    private Object interaction(JsonNode body) throws Exception {
      assertEquals(
          Set.of(
              "model",
              "store",
              "stream",
              "background",
              "system_instruction",
              "input",
              "generation_config",
              "response_format"),
          new HashSet<>(body.propertyNames()));
      assertEquals("fixture-sound", body.path("model").asString());
      assertFalse(body.path("store").asBoolean());
      assertFalse(body.path("stream").asBoolean());
      assertFalse(body.path("background").asBoolean());
      assertEquals(2, body.path("input").size());
      var text = body.path("input").get(0);
      assertEquals(Set.of("type", "text"), new HashSet<>(text.propertyNames()));
      assertEquals("text", text.path("type").asString());
      var input = JSON.readTree(text.path("text").asString());
      var audio = body.path("input").get(1);
      assertEquals(Set.of("type", "mime_type", "data"), new HashSet<>(audio.propertyNames()));
      assertEquals("audio", audio.path("type").asString());
      assertEquals("audio/wav", audio.path("mime_type").asString());
      byte[] wav = Base64.getDecoder().decode(audio.path("data").asString());
      var vector = vector(wav);
      assertEquals(
          JSON.valueToTree(
              Map.of(
                  "max_output_tokens", 8192, "thinking_summaries", "none", "tool_choice", "none")),
          body.path("generation_config"));
      var format = body.path("response_format");
      assertEquals(Set.of("type", "mime_type", "schema"), new HashSet<>(format.propertyNames()));
      assertEquals("text", format.path("type").asString());
      assertEquals("application/json", format.path("mime_type").asString());
      assertEquals("object", format.path("schema").path("type").asString());
      assertFalse(format.path("schema").path("additionalProperties").asBoolean());
      Object value;
      if (!input.has("question")) {
        assertEquals(0, input.size());
        assertTrue(
            body.path("system_instruction")
                .asString()
                .contains("Describe audible sounds solely for retrieval"));
        describeWavs.add(wav);
        value = Map.of("recall_text", RECALL);
      } else {
        String question = input.path("question").asString();
        assertTrue(List.of(QUESTION, HALF_QUESTION, CONFLICT_QUESTION).contains(question));
        boolean lowTone = vector.equals(List.of(1.0, 0.0));
        if (input.has("claims")) {
          assertEquals(Set.of("question", "claims"), new HashSet<>(input.propertyNames()));
          assertTrue(
              body.path("system_instruction")
                  .asString()
                  .contains("Independently verify every indexed claim"));
          assertEquals(1, input.path("claims").size());
          var claim = input.path("claims").get(0);
          assertEquals(Set.of("index", "claim"), new HashSet<>(claim.propertyNames()));
          assertEquals(0, claim.path("index").asInt());
          assertEquals(lowTone ? FACT : OTHER_FACT, claim.path("claim").asString());
          verifications.add(new Assessment(input, wav));
          value =
              Map.of("complete", true, "support", List.of(Map.of("index", 0, "supported", true)));
        } else {
          assertEquals(Set.of("question"), new HashSet<>(input.propertyNames()));
          assertTrue(
              body.path("system_instruction").asString().contains("Answer every requirement"));
          drafts.add(new Assessment(input, wav));
          boolean complete = !question.equals(HALF_QUESTION) && !vector.equals(List.of(-1.0, 0.0));
          value =
              Map.of(
                  "complete",
                  complete,
                  "claims",
                  complete ? List.of(lowTone ? FACT : OTHER_FACT) : List.of());
        }
      }
      return Map.of(
          "object",
          "interaction",
          "id",
          "synthetic-interaction",
          "model",
          "fixture-sound",
          "status",
          "completed",
          "steps",
          List.of(
              Map.of(
                  "type",
                  "model_output",
                  "content",
                  List.of(Map.of("type", "text", "text", JSON.writeValueAsString(value))))));
    }

    private static List<Double> vector(byte[] wav) {
      assertEquals(
          32044, wav.length, "The complete one-second canonical PCM window must be supplied");
      byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
      assertArrayEquals(AudioPcm.wav(pcm, 0, pcm.length), wav);
      var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
      long sum = 0, sumSquares = 0;
      int positiveCrossings = 0, previous = 0;
      while (samples.hasRemaining()) {
        int sample = samples.getShort();
        sum += sample;
        sumSquares += (long) sample * sample;
        if (previous <= 0 && sample > 0) {
          positiveCrossings++;
        }
        previous = sample;
      }
      if (sumSquares == 0) {
        return List.of(-1.0, 0.0);
      }
      assertTrue(Math.abs(sum) < 16000, "Audible fixtures must not be DC levels");
      double rms = Math.sqrt(sumSquares / 16000.0);
      assertTrue(rms > 8000 && rms < 9000, "The full tone waveform retains its audible energy");
      assertTrue(
          positiveCrossings == 440 || positiveCrossings == 880,
          "The complete one-second tone is classified by its actual frequency");
      return positiveCrossings == 440 ? List.of(1.0, 0.0) : List.of(0.0, 1.0);
    }

    private Object milvus(String path, JsonNode body) {
      assertEquals(COLLECTION, body.path("collectionName").asString());
      assertEquals("default", body.path("dbName").asString());
      if (path.endsWith("collections/has")) {
        return Map.of("has", schemas.containsKey(COLLECTION));
      }
      if (path.endsWith("collections/create")) {
        assertFalse(schemas.containsKey(COLLECTION));
        schemas.put(COLLECTION, body);
        rows.put(COLLECTION, new ConcurrentHashMap<>());
        return Map.of();
      }
      if (path.endsWith("collections/describe")) {
        return description(schemas.get(COLLECTION));
      }
      if (path.endsWith("indexes/describe")) {
        boolean dense = body.path("indexName").asString().equals("dense_index");
        return List.of(
            Map.of(
                "indexName",
                dense ? "dense_index" : "sparse_index",
                "fieldName",
                dense ? "dense" : "sparse",
                "indexType",
                dense ? "FLAT" : "SPARSE_INVERTED_INDEX",
                "metricType",
                dense ? "COSINE" : "BM25",
                "indexState",
                "Finished"));
      }
      if (path.endsWith("collections/load")) {
        return Map.of();
      }
      if (path.endsWith("entities/upsert")) {
        var ids = new ArrayList<String>();
        for (var row : body.path("data")) {
          String id = row.path("id").asString();
          assertEquals("org-main", row.path("workspace_id").asString());
          assertTrue(row.path("text").asString().matches("[0-9a-f]{64}"));
          rows.get(COLLECTION).put(id, row);
          ids.add(id);
        }
        return Map.of("upsertCount", ids.size(), "upsertIds", ids);
      }
      if (path.endsWith("entities/query")) {
        String filter = body.path("filter").asString();
        Set<String> ids = new HashSet<>();
        String revision = null;
        if (filter.startsWith("id in [")) {
          JSON.readTree(filter.substring(6)).forEach(id -> ids.add(id.asString()));
        } else {
          var matcher = Pattern.compile("revision_id == \"([^\"]+)\"").matcher(filter);
          assertTrue(
              matcher.matches(),
              "Receipt query must specify an exact generation or complete ID set");
          revision = matcher.group(1);
        }
        var selected = new ArrayList<Object>();
        for (var row : rows.get(COLLECTION).values()) {
          if ((!ids.isEmpty() && !ids.contains(row.path("id").asString()))
              || (revision != null && !revision.equals(row.path("revision_id").asString()))) {
            continue;
          }
          var fields = new LinkedHashMap<String, Object>();
          body.path("outputFields")
              .forEach(field -> fields.put(field.asString(), row.path(field.asString())));
          selected.add(fields);
        }
        return selected;
      }
      if (path.endsWith("entities/search")) {
        return search(body);
      }
      throw new AssertionError("Unexpected Milvus route " + path);
    }

    private List<Object> search(JsonNode request) {
      assertEquals("dense", request.path("annsField").asString());
      assertEquals(1, request.path("data").size());
      assertEquals(64, request.path("limit").asInt());
      String filter = request.path("filter").asString();
      assertTrue(filter.contains("workspace_id == \"org-main\""));
      var scope = new HashMap<String, String>();
      var matcher = SCOPE.matcher(filter);
      while (matcher.find()) {
        scope.put(matcher.group(1), matcher.group(2));
      }
      assertEquals(2, scope.size(), "Authorized public scope is applied before similarity scoring");
      var query = request.path("data").get(0);
      var scored = new ArrayList<Scored>();
      for (var row : rows.get(COLLECTION).values()) {
        if (!row.path("workspace_id").asString().equals("org-main")
            || !row.path("revision_id")
                .asString()
                .equals(scope.get(row.path("document_id").asString()))) {
          continue;
        }
        double dot =
            row.path("dense").get(0).asDouble() * query.get(0).asDouble()
                + row.path("dense").get(1).asDouble() * query.get(1).asDouble();
        if (dot > 0.5) {
          scored.add(new Scored(row, dot));
        }
      }
      scored.sort(
          Comparator.comparingDouble(Scored::similarity)
              .reversed()
              .thenComparing(value -> value.row().path("id").asString()));
      var result = new ArrayList<Object>();
      for (var hit : scored) {
        var row = hit.row();
        result.add(
            Map.of(
                "id",
                row.path("id").asString(),
                "workspace_id",
                "org-main",
                "document_id",
                row.path("document_id").asString(),
                "revision_id",
                row.path("revision_id").asString(),
                "distance",
                hit.similarity()));
      }
      return result;
    }

    private record Scored(JsonNode row, double similarity) {}

    private static Map<String, Object> description(JsonNode creation) {
      var fields = new ArrayList<Object>();
      for (var field : creation.path("schema").path("fields")) {
        var params = new ArrayList<Object>();
        for (var entry : field.path("elementTypeParams").properties()) {
          params.add(
              Map.of(
                  "key",
                  entry.getKey(),
                  "value",
                  entry.getValue().isString()
                      ? entry.getValue().asString()
                      : entry.getValue().toString()));
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("name", field.path("fieldName").asString());
        value.put("type", field.path("dataType").asString());
        value.put("primaryKey", field.path("isPrimary").asBoolean(false));
        value.put("autoId", false);
        value.put("nullable", false);
        value.put("params", params);
        if (field.path("fieldName").asString().equals("sparse")) {
          value.put("isFunctionOutput", true);
        }
        fields.add(value);
      }
      return Map.of(
          "collectionName",
          creation.path("collectionName"),
          "description",
          creation.path("description"),
          "consistencyLevel",
          "Strong",
          "autoId",
          false,
          "enableDynamicField",
          false,
          "fields",
          fields,
          "functions",
          List.of(
              Map.of(
                  "name",
                  "text_bm25",
                  "type",
                  "BM25",
                  "inputFieldNames",
                  List.of("text"),
                  "outputFieldNames",
                  List.of("sparse"))));
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
