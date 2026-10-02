package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.parser.AudioDecoder;
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
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual native decode and Spring publication; ASR and Milvus are local protocol fixtures. */
class AudioPublicationNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "audio-owner");
  private static final List<String> TRANSCRIPTS = List.of("首段合成转录。", "\n", "末尾样本合成转录。");
  @TempDir static Path directory;
  private String base;
  private HttpClient http;

  @Test
  void httpAudioUploadPublishesOnlyNonblankSpansAfterCompleteNativeCompilation() throws Exception {
    if (!"true".equals(System.getenv("RAG_AUDIO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native audio decoder IT opt-in is required");
    }
    Path ffmpeg = configuredPath("RAG_AUDIO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_AUDIO_DECODER_IT_FFPROBE");
    assertNotNull(directory);
    Path data = directory.resolve("audio-data");
    byte[] pcm = new byte[64_002];
    var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < pcm.length / 2; index++) {
      samples.putShort((short) (index * 31 + 17));
    }
    byte[] original = AudioPcm.wav(pcm, 0, pcm.length);
    try (var indexing = new IndexingTestServer();
        var asr = new AsrServer();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
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
              "--rag.image-ocr.enabled=false",
              "--rag.document-removal.enabled=false",
              "--rag.audio.enabled=true",
              "--rag.audio.ffmpeg-executable=" + ffmpeg,
              "--rag.audio.ffprobe-executable=" + ffprobe,
              "--rag.audio.decode-deadline-ms=10000",
              "--rag.audio.chunk-seconds=1",
              "--rag.audio.compilation-budget-ms=30000",
              "--rag.audio.base-url=" + asr.endpoint(),
              "--rag.audio.model=synthetic-publication-asr",
              "--rag.audio.api-key=" + asr.key,
              "--rag.audio.deadline-ms=5000",
              "--rag.audio.max-response-bytes=65536",
              "--rag.audio.allow-loopback-http=true",
              "--rag.ingestion.parse-timeout-ms=30000",
              "--rag.indexing.timeout-ms=15000")) {
        assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var runtime = json("GET", "/v1/config", null, null, 200);
        var capabilities = new ArrayList<String>();
        runtime.path("capabilities").forEach(value -> capabilities.add(value.asString()));
        assertTrue(capabilities.containsAll(List.of("audio_upload", "audio_index")));
        assertFalse(capabilities.contains("audio_answers"));
        assertFalse(capabilities.contains("answers"));
        assertFalse(capabilities.contains("visual_answers"));
        assertTrue(asr.requests.isEmpty(), "Startup must not transcribe");
        assertTrue(indexing.requests.isEmpty(), "Startup must not call model or vector services");

        var upload =
            json(
                "POST",
                "/v1/documents?filename=meeting.wav",
                original,
                "application/octet-stream",
                202);
        String document = upload.path("document_id").asString();
        String revision = upload.path("revision_id").asString();
        await("ingestions", upload.path("task_id").asString(), "parsed");
        var queued = json("POST", "/v1/documents/" + document + "/index", null, null, 202);
        var indexed = await("indexings", queued.path("task_id").asString(), "indexed");
        String publication = indexed.path("index_publication_id").asString();
        assertFalse(publication.isBlank());

        var store = context.getBean(SqliteAuthorityStore.class);
        var repository = context.getBean(IngestionRepository.class);
        var compilation =
            store.transaction(() -> repository.findAudioCompilation(revision).orElseThrow());
        assertEquals(ModelValues.sha256(original), compilation.sourceSha256());
        assertEquals(2001, compilation.durationMs());
        assertEquals(
            List.of(0, 1, 2),
            compilation.spans().stream().map(AudioTranscriptSpan::ordinal).toList());
        assertEquals(
            List.of(0L, 1000L, 2000L),
            compilation.spans().stream().map(AudioTranscriptSpan::startMs).toList());
        assertEquals(
            List.of(1000L, 2000L, 2001L),
            compilation.spans().stream().map(AudioTranscriptSpan::endMs).toList());
        assertEquals(
            TRANSCRIPTS, compilation.spans().stream().map(AudioTranscriptSpan::text).toList());
        assertEquals(context.getBean(AudioDecoder.class).revision(), compilation.decoderRevision());
        assertEquals(context.getBean(AudioModels.class).revision(), compilation.modelRevision());
        assertEquals(
            context.getBean(AudioCompilationService.class).revision(),
            compilation.compilerRevision());
        assertArrayEquals(original, store.transaction(() -> repository.original(document)));
        var text = context.getBean(IngestionService.class).parsedEvidence(OWNER, document);
        assertTrue(text.pages().isEmpty());
        assertTrue(text.segments().isEmpty());

        var snapshot =
            context
                .getBean(EvidenceService.class)
                .snapshot(
                    OWNER,
                    DocumentSelection.selected(List.of(document)),
                    context.getBean(IndexTarget.class));
        assertEquals(1, snapshot.publications().size());
        assertEquals(publication, snapshot.publications().getFirst().publicationId());
        assertEquals(2, snapshot.publications().getFirst().segmentCount());
        assertPublishedAudioOnly(data, publication);
        assertEquals(3, asr.requests.size());
        assertTrue(asr.replies.isEmpty());
        assertArrayEquals(AudioPcm.wav(pcm, 0, 32_000), wavPayload(asr.requests.get(0)));
        assertArrayEquals(AudioPcm.wav(pcm, 32_000, 64_000), wavPayload(asr.requests.get(1)));
        assertArrayEquals(AudioPcm.wav(pcm, 64_000, 64_002), wavPayload(asr.requests.get(2)));
        var embedded = new ArrayList<String>();
        indexing.requests.stream()
            .filter(request -> request.path().equals("/embeddings"))
            .forEach(
                request ->
                    request.body().path("input").forEach(value -> embedded.add(value.asString())));
        assertEquals(List.of(TRANSCRIPTS.get(0), TRANSCRIPTS.get(2)), embedded);
      }
    }
  }

  private static void assertPublishedAudioOnly(Path data, String publication) throws Exception {
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + data.resolve("java-library.db"), config.toProperties())) {
      for (var expected :
          Map.of(
                  "corpus_pages",
                  0,
                  "corpus_segments",
                  0,
                  "audio_compilations",
                  1,
                  "audio_spans",
                  3,
                  "index_publication_entries",
                  0,
                  "image_publication_entries",
                  0,
                  "audio_publication_entries",
                  2)
              .entrySet()) {
        try (var query = connection.createStatement();
            var rows = query.executeQuery("SELECT COUNT(*) FROM " + expected.getKey())) {
          assertTrue(rows.next());
          assertEquals(expected.getValue().intValue(), rows.getInt(1), expected.getKey());
        }
      }
      try (var query =
          connection.prepareStatement(
              "SELECT e.audio_span_id,e.physical_segment_id,e.entry_sha256,s.ordinal,s.index_ordinal,p.projection_generation_id FROM audio_publication_entries e JOIN audio_spans s ON s.id=e.audio_span_id JOIN index_publications p ON p.id=e.publication_id JOIN active_corpus_publications a ON a.publication_id=p.id WHERE p.id=? ORDER BY s.index_ordinal")) {
        query.setString(1, publication);
        try (var rows = query.executeQuery()) {
          for (int index = 0; index < 2; index++) {
            assertTrue(rows.next());
            assertEquals(index * 2, rows.getInt("ordinal"));
            assertEquals(index, rows.getInt("index_ordinal"));
            assertEquals(
                RetrievalProjection.physicalSegmentId(
                    rows.getString("projection_generation_id"), rows.getString("audio_span_id")),
                rows.getString("physical_segment_id"));
            assertTrue(rows.getString("entry_sha256").matches("[a-f0-9]{64}"));
          }
          assertFalse(rows.next());
        }
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
    return fail("Synthetic audio publication task did not finish");
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

  private static byte[] wavPayload(AsrRequest request) {
    String prefix = "multipart/form-data; boundary=";
    assertNotNull(request.contentType());
    assertTrue(request.contentType().startsWith(prefix));
    String boundary = request.contentType().substring(prefix.length());
    String[] parts =
        new String(request.body(), StandardCharsets.ISO_8859_1)
            .split(Pattern.quote("--" + boundary), -1);
    assertEquals(4, parts.length);
    assertEquals("", parts[0]);
    assertEquals("--\r\n", parts[3]);
    assertEquals(
        "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nsynthetic-publication-asr\r\n",
        parts[1]);
    String header =
        "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
    assertTrue(parts[2].startsWith(header));
    assertTrue(parts[2].endsWith("\r\n"));
    return parts[2]
        .substring(header.length(), parts[2].length() - 2)
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  private static Path configuredPath(String variable) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Explicit native audio decoder paths are required");
    }
    return Path.of(value);
  }

  private record AsrRequest(String contentType, byte[] body) {}

  private static final class AsrServer implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(3);
    private final List<AsrRequest> requests = new CopyOnWriteArrayList<>();
    private final String key = UUID.randomUUID().toString();

    private AsrServer() throws IOException {
      replies.addAll(TRANSCRIPTS);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/audio/transcriptions", this::reply);
      server.start();
    }

    private String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void reply(HttpExchange exchange) throws IOException {
      try (exchange) {
        if (!"POST".equals(exchange.getRequestMethod())
            || !"/v1/audio/transcriptions".equals(exchange.getRequestURI().getPath())
            || !("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        byte[] request = exchange.getRequestBody().readNBytes(1_048_577);
        if (request.length > 1_048_576) {
          exchange.sendResponseHeaders(413, -1);
          return;
        }
        requests.add(
            new AsrRequest(exchange.getRequestHeaders().getFirst("Content-Type"), request));
        String text = replies.poll();
        if (text == null) {
          exchange.sendResponseHeaders(503, -1);
          return;
        }
        byte[] body = JSON.writeValueAsBytes(Map.of("text", text, "start", 99999, "end", 999999));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
