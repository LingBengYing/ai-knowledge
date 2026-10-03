package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import tools.jackson.databind.node.ObjectNode;

/** Real Spring HTTP, FFmpeg and isolated Java workers; all remote calls are synthetic loopback. */
class AudioVectorRetrievalMainlineNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION = "上海住宿上限是多少？";
  private static final String TRANSCRIPT = "上海住宿上限为650元。";
  @TempDir Path directory;

  @Test
  void originalMultiSpanWavesRecallTheCorrectSavedTailAndRestartWithoutModels() throws Exception {
    assertEquals("true", System.getenv("RAG_AUDIO_VECTOR_IT_ENABLED"));
    byte[] oldPcm = pcm(1200, 0, -2000), newPcm = pcm(1200, 0, 2000), queryPcm = pcm(0, 0, -2200);
    byte[] oldAudio = AudioPcm.wav(oldPcm, 0, oldPcm.length),
        newAudio = AudioPcm.wav(newPcm, 0, newPcm.length);
    byte[] queryAudio = AudioPcm.wav(queryPcm, 0, queryPcm.length);
    assertFalse(Arrays.equals(oldAudio, queryAudio));
    Path data = directory.resolve("data");
    try (var remote = new Providers();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      String oldDocument, privateDocument;
      try (var app = application(remote, data, false)) {
        assertTrue(remote.requests.isEmpty(), "Startup does not probe models or projections");
        String base = base(app);
        oldDocument = publish(http, base, "owner", "old-wave.wav", oldAudio);
        privateDocument = publish(http, base, "other-owner", "private-wave.wav", oldAudio);
        var legacy =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/audio-answers",
                JSON.writeValueAsBytes(
                    Map.of("question", QUESTION, "document_ids", List.of(oldDocument))),
                "application/json",
                200);
        assertEquals("abstained", legacy.path("status").asString());
        assertEquals("no_evidence", legacy.path("reason").asString());
        assertEquals(0, remote.audioEmbeddings());
      }
      String sourceUrl, contentUrl;
      JsonNode citation;
      try (var app = application(remote, data, true)) {
        String base = base(app);
        assertTrue(
            json(http, base, "owner", "GET", "/v1/config", null, null, 200)
                .path("capabilities")
                .toString()
                .contains("audio_vector_retrieval"));
        String newDocument = publish(http, base, "owner", "new-wave.wav", newAudio);
        var originalPublication = row(http, base, oldDocument);
        var missing = json(http, base, "owner", "GET", vectorPath(oldDocument), null, null, 200);
        assertEquals("missing", missing.path("status").asString());
        assertEquals(10, missing.size());
        assertTrue(missing.path("vector_generation_id").isNull());
        assertEquals(0, remote.audioEmbeddings());
        assertEquals(
            404,
            request(http, base, "owner", "GET", vectorPath(privateDocument), null, null)
                .statusCode());
        byte[] attached =
            JSON.writeValueAsBytes(
                Map.of(
                    "question",
                    QUESTION,
                    "mode",
                    "audio",
                    "document_ids",
                    List.of(oldDocument, newDocument),
                    "attachments",
                    List.of(
                        Map.of(
                            "filename",
                            "query.wav",
                            "media_type",
                            "audio/wav",
                            "content_base64",
                            Base64.getEncoder().encodeToString(queryAudio)))));
        int before = remote.asrWavs.size();
        var unavailable =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/attachment-answers",
                attached,
                "application/json",
                200);
        assertEquals(
            "audio_vector_required",
            unavailable.path("result").path("reason").asString(),
            unavailable.toString());
        assertEquals(0, remote.audioEmbeddings());
        assertEquals(
            before + 3,
            remote.asrWavs.size(),
            "One complete query preparation precedes receipt validation");
        before = remote.asrWavs.size();
        var oldVector = json(http, base, "owner", "POST", vectorPath(oldDocument), null, null, 200);
        var newVector = json(http, base, "owner", "POST", vectorPath(newDocument), null, null, 200);
        assertEquals("available", oldVector.path("status").asString());
        assertEquals("available", newVector.path("status").asString());
        assertNotEquals(
            oldVector.path("vector_generation_id"), newVector.path("vector_generation_id"));
        assertEquals(
            4,
            remote.audioEmbeddings(),
            "All two published speech spans of both originals are embedded");
        assertEquals(before, remote.asrWavs.size(), "Building vectors does not repeat ASR");
        assertEquals(
            oldVector, json(http, base, "owner", "POST", vectorPath(oldDocument), null, null, 200));
        assertEquals(4, remote.audioEmbeddings());
        assertTrue(originalPublication.path("can_reindex").asBoolean());
        var expectedPublication = ((ObjectNode) originalPublication).deepCopy();
        expectedPublication.put("can_reindex", false);
        assertEquals(
            expectedPublication,
            row(http, base, oldDocument),
            "Old text publication remains identical");
        var answer =
            json(
                http,
                base,
                "owner",
                "POST",
                "/v1/attachment-answers",
                attached,
                "application/json",
                200);
        var result = answer.path("result");
        assertEquals("answered", result.path("status").asString(), answer.toString());
        assertTrue(result.path("answer").asString().contains("650"));
        assertEquals(1, result.path("citations").size());
        citation = result.path("citations").get(0);
        assertEquals(oldDocument, citation.path("document_id").asString());
        assertEquals(ModelValues.sha256(oldAudio), citation.path("source_sha256").asString());
        assertEquals("audio_span", citation.path("kind").asString());
        assertEquals(2000, citation.path("start_ms").asLong());
        assertEquals(3000, citation.path("end_ms").asLong());
        assertEquals("machine_asr", citation.path("text_origin").asString());
        assertEquals("server_chunk", citation.path("time_precision").asString());
        sourceUrl = citation.path("source_url").asString();
        contentUrl = citation.path("content_url").asString();
        assertEquals(
            citation,
            json(http, base, "owner", "GET", sourceUrl, null, null, 200).path("citation"));
        assertArrayEquals(
            oldAudio, request(http, base, "owner", "GET", contentUrl, null, null).body());
        var ranged =
            http.send(
                HttpRequest.newBuilder(URI.create(base + contentUrl))
                    .header("Origin", base)
                    .header("X-Workspace-Id", "org-main")
                    .header("X-Principal-Id", "owner")
                    .header("Range", "bytes=64044-64075")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, ranged.statusCode());
        assertArrayEquals(Arrays.copyOfRange(oldAudio, 64044, 64076), ranged.body());
        assertEquals(
            7,
            remote.audioEmbeddings(),
            "Quiet query chunks and final query waveform are all embedded");
        assertEquals(before + 3, remote.asrWavs.size(), "Each query chunk is transcribed once");
        var expected =
            List.of(
                AudioPcm.wav(oldPcm, 0, 32000),
                AudioPcm.wav(oldPcm, 64000, 96000),
                AudioPcm.wav(newPcm, 0, 32000),
                AudioPcm.wav(newPcm, 64000, 96000),
                AudioPcm.wav(queryPcm, 0, 32000),
                AudioPcm.wav(queryPcm, 32000, 64000),
                AudioPcm.wav(queryPcm, 64000, 96000));
        var embeds =
            remote.requests.stream().filter(call -> call.path().endsWith(":embedContent")).toList();
        for (int index = 0; index < expected.size(); index++) {
          assertArrayEquals(expected.get(index), embeddingWav(embeds.get(index).body()));
        }
        var searches =
            remote.requests.stream()
                .filter(
                    call ->
                        call.path().endsWith("entities/search")
                            && call.body()
                                .path("collectionName")
                                .asString()
                                .equals("java_audio_native_fixture"))
                .toList();
        assertEquals(3, searches.size());
        for (var call : searches) {
          assertEquals("dense", call.body().path("annsField").asString());
          String filter = call.body().path("filter").asString();
          assertTrue(filter.contains(oldDocument) && filter.contains(newDocument));
          assertTrue(filter.contains(oldVector.path("vector_generation_id").asString()));
          assertTrue(filter.contains(newVector.path("vector_generation_id").asString()));
          assertFalse(filter.contains(privateDocument));
        }
        var generation =
            remote.requests.stream()
                .filter(
                    call ->
                        call.path().endsWith("chat/completions")
                            && call.body().path("model").asString().equals("fixture-text"))
                .toList();
        assertEquals(1, generation.size());
        var proof = JSON.readTree(textInput(generation.getFirst().body()));
        assertEquals(QUESTION, proof.path("question").asString());
        assertEquals(TRANSCRIPT, proof.path("evidence").get(0).path("text").asString());
        assertFalse(proof.toString().contains("query.wav"));
      }
      int calls = remote.requests.size(), asrCalls = remote.asrWavs.size();
      try (var app = application(remote, data, true)) {
        String base = base(app);
        assertEquals(
            citation,
            json(http, base, "owner", "GET", sourceUrl, null, null, 200).path("citation"));
        assertArrayEquals(
            oldAudio, request(http, base, "owner", "GET", contentUrl, null, null).body());
        assertEquals(calls, remote.requests.size());
        assertEquals(asrCalls, remote.asrWavs.size());
      }
    }
  }

  private ConfigurableApplicationContext application(Providers remote, Path data, boolean enabled) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(remote.environment());
    Path ffmpeg = nativePath("RAG_VIDEO_DECODER_IT_FFMPEG"),
        ffprobe = nativePath("RAG_VIDEO_DECODER_IT_FFPROBE");
    Path tesseract = nativePath("RAG_IMAGE_OCR_IT_EXECUTABLE");
    String decoderRevision;
    try (var decoder = new ProcessAudioDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15))) {
      decoderRevision = decoder.revision();
    }
    var properties =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--rag.environment=test",
                "--rag.workspace-id=org-main",
                "--rag.auth-mode=development_headers",
                "--rag.data-directory=" + data,
                "--rag.ingestion.enabled=true",
                "--rag.indexing.enabled=true",
                "--rag.answers.enabled=true",
                "--rag.visual.enabled=true",
                "--rag.audio.enabled=true",
                "--rag.video.enabled=true",
                "--rag.query-attachments.enabled=true",
                "--rag.image-ocr.enabled=true",
                "--rag.image-ocr.executable=" + tesseract,
                "--rag.image-ocr.language=eng",
                "--rag.image-ocr.revision=native-audio-vector-fixture-v1",
                "--rag.audio.chunk-seconds=1",
                "--rag.audio.ffmpeg-executable=" + ffmpeg,
                "--rag.audio.ffprobe-executable=" + ffprobe,
                "--rag.video.ffmpeg-executable=" + ffmpeg,
                "--rag.video.ffprobe-executable=" + ffprobe,
                "--rag.answers.timeout-ms=30000",
                "--rag.ingestion.parse-timeout-ms=15000",
                "--rag.indexing.timeout-ms=15000",
                "--rag.audio-embedding.enabled=" + enabled,
                "--rag.audio-embedding.base-url=" + remote.endpoint(),
                "--rag.audio-embedding.model=fixture-audio-embedding",
                "--rag.audio-embedding.api-key=synthetic-fixture-credential",
                "--rag.audio-embedding.revision=fixture-audio-v1",
                "--rag.audio-embedding.dimensions=2",
                "--rag.audio-embedding.decoder-revision=" + decoderRevision,
                "--rag.audio-embedding.allow-loopback-http=true",
                "--rag.audio-embedding.milvus.endpoint=" + remote.endpoint(),
                "--rag.audio-embedding.milvus.token=synthetic-fixture-credential",
                "--rag.audio-embedding.milvus.collection=java_audio_native_fixture",
                "--rag.audio-embedding.milvus.allow-loopback-http=true"));
    for (String prefix :
        List.of(
            "rag.visual",
            "rag.audio",
            "rag.video.asr",
            "rag.video.vision",
            "rag.query-attachments.ranking")) {
      properties.add("--" + prefix + ".base-url=" + remote.endpoint());
      properties.add(
          "--"
              + prefix
              + ".model="
              + (prefix.endsWith("ranking")
                  ? "fixture-ranking"
                  : prefix.endsWith("asr") || prefix.equals("rag.audio")
                      ? "fixture-asr"
                      : "fixture-vision"));
      properties.add("--" + prefix + ".api-key=synthetic-fixture-credential");
      properties.add("--" + prefix + ".allow-loopback-http=true");
    }
    return application.run(properties.toArray(String[]::new));
  }

  private static byte[] pcm(int... chunks) {
    var samples = ByteBuffer.allocate(chunks.length * 32000).order(ByteOrder.LITTLE_ENDIAN);
    for (int chunk : chunks) {
      for (int sample = 0; sample < 16000; sample++) {
        samples.putShort((short) chunk);
      }
    }
    return samples.array();
  }

  private static byte[] embeddingWav(JsonNode body) {
    return Base64.getDecoder()
        .decode(
            body.path("content").path("parts").get(0).path("inlineData").path("data").asString());
  }

  private static byte[] multipartWav(byte[] raw, String type) {
    String boundary = type.substring("multipart/form-data; boundary=".length());
    String[] parts =
        new String(raw, StandardCharsets.ISO_8859_1).split(Pattern.quote("--" + boundary), -1);
    assertEquals(4, parts.length);
    int start = parts[2].indexOf("\r\n\r\n") + 4;
    return parts[2].substring(start, parts[2].length() - 2).getBytes(StandardCharsets.ISO_8859_1);
  }

  private static String textInput(JsonNode body) {
    var content = body.path("messages").get(1).path("content");
    return content.isString() ? content.asString() : content.get(0).path("text").asString();
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

  private static String vectorPath(String document) {
    return "/v1/documents/" + document + "/audio-vector";
  }

  private static String publish(
      HttpClient http, String base, String actor, String filename, byte[] image) throws Exception {
    var uploaded =
        json(
            http,
            base,
            actor,
            "POST",
            "/v1/documents?filename=" + filename,
            image,
            "application/octet-stream",
            202);
    String document = uploaded.path("document_id").asString();
    await(http, base, actor, "ingestions", uploaded.path("task_id").asString(), "parsed");
    var indexed =
        json(http, base, actor, "POST", "/v1/documents/" + document + "/index", null, null, 202);
    await(http, base, actor, "indexings", indexed.path("task_id").asString(), "indexed");
    return document;
  }

  private static void await(
      HttpClient http, String base, String actor, String route, String task, String expected)
      throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < until) {
      var value = json(http, base, actor, "GET", "/v1/" + route + "/" + task, null, null, 200);
      String status = value.path("state").asString();
      if (expected.equals(status)) {
        return;
      }
      assertFalse(List.of("failed", "cancelled").contains(status), value.toString());
      Thread.sleep(25);
    }
    fail("Native task did not reach " + expected);
  }

  private static JsonNode row(HttpClient http, String base, String document) throws Exception {
    var items =
        json(http, base, "owner", "GET", "/v1/management/documents", null, null, 200).path("items");
    for (var item : items) {
      if (document.equals(item.path("document_id").asString())) {
        return item;
      }
    }
    throw new AssertionError("Published audio must remain visible");
  }

  private static JsonNode json(
      HttpClient http,
      String base,
      String actor,
      String method,
      String path,
      byte[] body,
      String type,
      int status)
      throws Exception {
    var response = request(http, base, actor, method, path, body, type);
    assertEquals(
        status,
        response.statusCode(),
        new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
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
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(130))
            .header("Origin", base)
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", actor);
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

  /** A waveform-dependent dense fixture; ASR content is deliberately identical across originals. */
  private static final class Providers implements AutoCloseable {
    private static final Pattern SCOPE =
        Pattern.compile(
            "\\(document_id == \"([A-Za-z0-9._:-]+)\" && revision_id == \"([A-Za-z0-9._:-]+)\"\\)");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();
    private final Map<String, Map<String, JsonNode>> rows = new ConcurrentHashMap<>();
    private final List<Call> requests = new CopyOnWriteArrayList<>();

    private record Call(String path, JsonNode body) {}

    private final List<byte[]> asrWavs = new CopyOnWriteArrayList<>();

    Providers() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    int audioEmbeddings() {
      return (int) requests.stream().filter(call -> call.path().endsWith(":embedContent")).count();
    }

    Map<String, Object> environment() {
      var values = new LinkedHashMap<String, Object>();
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        values.put("RAG_" + kind + "_BASE_URL", endpoint().toString());
        values.put("RAG_" + kind + "_MODEL", "fixture-text");
        values.put("RAG_" + kind + "_API_KEY", "synthetic-fixture-credential");
      }
      values.put("RAG_EMBEDDING_DIMENSIONS", "2");
      values.put("RAG_EMBEDDING_REVISION", "fixture-text-v1");
      values.put("RAG_MILVUS_ENDPOINT", endpoint().toString());
      values.put("RAG_MILVUS_TOKEN", "synthetic-fixture-credential");
      values.put("RAG_MILVUS_COLLECTION", "java_text_native_fixture");
      values.put("RAG_WORKSPACE_ID", "org-main");
      values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
      values.put("RAG_TEXT_DEADLINE_MS", "10000");
      return values;
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        byte[] raw = exchange.getRequestBody().readAllBytes();
        var body =
            path.endsWith("audio/transcriptions") ? JSON.createObjectNode() : JSON.readTree(raw);
        requests.add(new Call(path, body));
        Object response;
        if (path.endsWith(":embedContent")) {
          assertEquals("/v1beta/models/fixture-audio-embedding:embedContent", path);
          assertEquals(
              "synthetic-fixture-credential",
              exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
          assertEquals(
              Set.of("content", "embedContentConfig"), new HashSet<>(body.propertyNames()));
          assertEquals(Set.of("parts"), new HashSet<>(body.path("content").propertyNames()));
          assertEquals(1, body.path("content").path("parts").size());
          var part = body.path("content").path("parts").get(0);
          assertEquals(Set.of("inlineData"), new HashSet<>(part.propertyNames()));
          assertEquals(
              Set.of("mimeType", "data"), new HashSet<>(part.path("inlineData").propertyNames()));
          assertEquals("audio/wav", part.path("inlineData").path("mimeType").asString());
          assertEquals(
              Set.of("outputDimensionality", "autoTruncate"),
              new HashSet<>(body.path("embedContentConfig").propertyNames()));
          assertEquals(2, body.path("embedContentConfig").path("outputDimensionality").asInt());
          assertFalse(body.path("embedContentConfig").path("autoTruncate").asBoolean());
          response = Map.of("embedding", Map.of("values", vector(embeddingWav(body))));
        } else if (path.endsWith("audio/transcriptions")) {
          byte[] wav = multipartWav(raw, exchange.getRequestHeaders().getFirst("Content-Type"));
          asrWavs.add(wav);
          var vector = vector(wav);
          response = Map.of("text", vector.equals(List.of(-1.0, 0.0)) ? "" : TRANSCRIPT);
        } else if (path.equals("/embeddings")) {
          var data = new ArrayList<Object>();
          for (int i = 0; i < body.path("input").size(); i++) {
            data.add(Map.of("index", i, "embedding", List.of(1.0, 0.0)));
          }
          response = Map.of("data", data);
        } else if (path.equals("/chat/completions")) {
          Object value;
          if (body.path("model").asString().equals("fixture-ranking")) {
            var ranks = new ArrayList<Object>();
            for (var part : body.path("messages").get(1).path("content")) {
              if (part.has("text")) {
                var marker = JSON.readTree(part.path("text").asString());
                if (marker.path("role").asString().equals("authorized_candidate")) {
                  ranks.add(Map.of("index", marker.path("index").asInt(), "score", 0.99));
                }
              }
            }
            value = Map.of("rankings", ranks);
          } else {
            var input = JSON.readTree(textInput(body));
            var quotes = new ArrayList<Object>();
            for (var evidence : input.path("evidence")) {
              quotes.add(
                  Map.of(
                      "evidence_id",
                      evidence.path("evidence_id").asString(),
                      "quote",
                      evidence.path("text").asString()));
            }
            value = Map.of("refused", quotes.isEmpty(), "quotes", quotes);
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
                          Map.of("role", "assistant", "content", JSON.writeValueAsString(value)))));
        } else if (path.equals("/rerank")) {
          var ranks = new ArrayList<Object>();
          for (int i = 0; i < body.path("documents").size(); i++) {
            ranks.add(Map.of("index", i, "relevance_score", 100.0 - i));
          }
          response = Map.of("results", ranks);
        } else {
          String collection = body.path("collectionName").asString();
          Object data;
          if (path.endsWith("collections/has")) {
            data = Map.of("has", schemas.containsKey(collection));
          } else if (path.endsWith("collections/create")) {
            schemas.put(collection, body);
            rows.put(collection, new ConcurrentHashMap<>());
            data = Map.of();
          } else if (path.endsWith("collections/describe")) {
            data = description(schemas.get(collection));
          } else if (path.endsWith("indexes/describe")) {
            boolean dense = body.path("indexName").asString().equals("dense_index");
            data =
                List.of(
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
          } else if (path.endsWith("collections/load")) {
            data = Map.of();
          } else if (path.endsWith("entities/upsert")) {
            var ids = new ArrayList<String>();
            for (var row : body.path("data")) {
              String id = row.path("id").asString();
              rows.get(collection).put(id, row);
              ids.add(id);
            }
            data = Map.of("upsertCount", ids.size(), "upsertIds", ids);
          } else if (path.endsWith("entities/query")) {
            String filter = body.path("filter").asString();
            Set<String> ids = new HashSet<>();
            String revision = null;
            if (filter.startsWith("id in [")) {
              JSON.readTree(filter.substring(6)).forEach(id -> ids.add(id.asString()));
            } else {
              var matcher = Pattern.compile("revision_id == \"([^\"]+)\"").matcher(filter);
              if (matcher.matches()) {
                revision = matcher.group(1);
              }
            }
            var selected = new ArrayList<Object>();
            for (var row : rows.get(collection).values()) {
              if ((!ids.isEmpty() && !ids.contains(row.path("id").asString()))
                  || (revision != null && !revision.equals(row.path("revision_id").asString()))) {
                continue;
              }
              var fields = new LinkedHashMap<String, Object>();
              body.path("outputFields")
                  .forEach(field -> fields.put(field.asString(), row.path(field.asString())));
              selected.add(fields);
            }
            data = selected;
          } else if (path.endsWith("entities/search")) {
            data = search(collection, body);
          } else {
            exchange.sendResponseHeaders(404, -1);
            return;
          }
          response = Map.of("code", 0, "data", data);
        }
        byte[] responseBytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
      }
    }

    private static List<Double> vector(byte[] wav) {
      assertTrue(wav.length > 44 && wav.length <= 960044 && (wav.length - 44) % 2 == 0);
      byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
      assertArrayEquals(
          AudioPcm.wav(pcm, 0, pcm.length), wav, "Every canonical WAV byte is supplied");
      long sum = 0;
      var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
      while (samples.hasRemaining()) {
        sum += samples.getShort();
      }
      return sum == 0 ? List.of(-1.0, 0.0) : sum < 0 ? List.of(1.0, 0.0) : List.of(0.0, 1.0);
    }

    private List<Object> search(String collection, JsonNode request) {
      if (!collection.startsWith("java_audio_")) {
        return List.of();
      }
      assertEquals("dense", request.path("annsField").asString());
      var scope = new HashMap<String, String>();
      var matcher = SCOPE.matcher(request.path("filter").asString());
      while (matcher.find()) {
        scope.put(matcher.group(1), matcher.group(2));
      }
      var result = new ArrayList<Object>();
      var query = request.path("data").get(0);
      for (var row : rows.get(collection).values()) {
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
                  dot));
        }
      }
      return result;
    }

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
