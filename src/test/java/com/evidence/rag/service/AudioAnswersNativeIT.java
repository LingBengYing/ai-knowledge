package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.parser.AudioDecoder;
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
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Real native decode and HTTP answers; all model and Milvus endpoints are local protocol fixtures.
 */
class AudioAnswersNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "audio-owner");
  private static final String CODE = "星港项目的识别码为A-42。";
  private static final String BUDGET = "星港项目的预算为47万元。";
  private static final String QUESTION = "星港项目的识别码是什么？星港项目的预算是多少？";
  private static final List<String> TRANSCRIPTS = List.of(CODE, "\n", BUDGET);
  @TempDir static Path directory;
  private String base;
  private HttpClient http;

  @Test
  void nativeUploadAnswersWithRealTimeRangesAndRechecksEntireSelectedScope() throws Exception {
    if (!"true".equals(System.getenv("RAG_AUDIO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native audio decoder IT opt-in is required");
    }
    Path ffmpeg = configuredPath("RAG_AUDIO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_AUDIO_DECODER_IT_FFPROBE");
    assertNotNull(directory);
    Path data = directory.resolve("audio-answers-data");
    byte[] pcm = new byte[64_002];
    var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < pcm.length / 2; index++) {
      samples.putShort((short) (index * 31 + 17));
    }
    byte[] original = AudioPcm.wav(pcm, 0, pcm.length);
    try (var indexing = new IndexingTestServer();
        var answers = new AnswerProtocolServer();
        var asr = new AsrServer();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      http = client;
      var defaults = new LinkedHashMap<String, Object>(answers.environment());
      for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
        defaults.put("RAG_" + kind + "_API_KEY", UUID.randomUUID().toString());
      }
      // The same worker-written rows serve retrieval; no manual projection installation or seeding.
      defaults.put("RAG_EMBEDDING_BASE_URL", indexing.endpoint().toString());
      defaults.put("RAG_EMBEDDING_MODEL", "fixture-model");
      defaults.put("RAG_MILVUS_ENDPOINT", indexing.endpoint().toString());
      defaults.put("RAG_MILVUS_TOKEN", UUID.randomUUID().toString());
      defaults.put("RAG_MILVUS_COLLECTION", indexing.settings().projection().collection());
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
              "--rag.answers.enabled=true",
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
              "--rag.audio.model=synthetic-answers-asr",
              "--rag.audio.api-key=" + asr.key,
              "--rag.audio.deadline-ms=5000",
              "--rag.audio.max-response-bytes=65536",
              "--rag.audio.allow-loopback-http=true",
              "--rag.ingestion.parse-timeout-ms=30000",
              "--rag.indexing.timeout-ms=15000",
              "--rag.answers.timeout-ms=15000")) {
        assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
        assertInstanceOf(ProcessAudioDecoder.class, context.getBean(AudioDecoder.class));
        assertInstanceOf(OpenAiCompatibleAudioModels.class, context.getBean(AudioModels.class));
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var runtime = json("GET", "/v1/config", null, null, 200);
        var capabilities = new ArrayList<String>();
        runtime.path("capabilities").forEach(value -> capabilities.add(value.asString()));
        assertTrue(
            capabilities.containsAll(
                List.of("audio_upload", "audio_index", "audio_answers", "audio_sources")));
        assertFalse(capabilities.contains("visual_answers"));
        assertTrue(asr.requests.isEmpty(), "Startup must not transcribe");
        assertTrue(
            indexing.requests.isEmpty(), "Startup must not call embedding or vector services");
        assertTrue(answers.requests.isEmpty(), "Startup must not call answer models");

        var audio = uploadAndIndex("meeting.wav", original);
        var text =
            uploadAndIndex(
                "selected-context.txt", "旁支项目的识别码为B-7。".getBytes(StandardCharsets.UTF_8));
        String document = audio.document();
        var store = context.getBean(SqliteAuthorityStore.class);
        var repository = context.getBean(IngestionRepository.class);
        var compilation =
            store.transaction(
                () -> repository.findAudioCompilation(audio.revision()).orElseThrow());
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
        var parsed = context.getBean(IngestionService.class).parsedEvidence(OWNER, document);
        assertTrue(parsed.pages().isEmpty());
        assertTrue(parsed.segments().isEmpty());
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
        assertEquals(List.of(CODE, BUDGET, "旁支项目的识别码为B-7。"), embedded);
        assertEquals(2, indexing.committedUpserts.size());

        var evidence = context.getBean(EvidenceService.class);
        var target = context.getBean(IndexTarget.class);
        var completeSelection = DocumentSelection.selected(List.of(text.document(), document));
        assertEquals(2, evidence.snapshot(OWNER, completeSelection, target).publications().size());
        var audioScope = DocumentSelection.selected(List.of(document));
        var audioPublication =
            evidence.snapshot(OWNER, audioScope, target).publications().getFirst();
        assertEquals(audio.publication(), audioPublication.publicationId());
        assertEquals(2, audioPublication.segmentCount());

        var answer =
            json(
                "POST",
                "/v1/audio-answers",
                JSON.writeValueAsBytes(
                    Map.of(
                        "question", QUESTION, "document_ids", List.of(text.document(), document))),
                "application/json",
                200);
        assertEquals("answered", answer.path("status").asString(), answer.toString());
        assertTrue(answer.path("reason").isNull());
        assertTrue(answer.path("answer").asString().contains("A-42"));
        assertTrue(answer.path("answer").asString().contains("47万元"));
        assertFalse(answer.path("answer").asString().contains("B-7"));
        int indexRequests = indexing.requests.size();
        int answerRequests = answers.requests.size();
        String answerId = answer.path("answer_id").asString();
        assertFalse(answerId.isBlank());
        assertEquals(
            Set.of("answer_id", "status", "answer", "reason", "citations"),
            new HashSet<>(answer.propertyNames()));
        var citations = new ArrayList<JsonNode>();
        answer.path("citations").forEach(citations::add);
        assertEquals(2, citations.size());
        var byTime =
            citations.stream()
                .sorted(Comparator.comparingLong(citation -> citation.path("start_ms").asLong()))
                .toList();
        assertEquals(
            List.of(0L, 2000L),
            byTime.stream().map(citation -> citation.path("start_ms").asLong()).toList());
        assertEquals(
            List.of(1000L, 2001L),
            byTime.stream().map(citation -> citation.path("end_ms").asLong()).toList());
        assertTrue(byTime.getFirst().path("quote").asString().contains("A-42"));
        assertTrue(byTime.getLast().path("quote").asString().contains("47万元"));
        for (var citation : citations) {
          assertTypedCitation(citation, answerId, audio, compilation.compilerRevision(), original);
          var source = json("GET", citation.path("source_url").asString(), null, null, 200);
          assertEquals(Set.of("answer_id", "citation"), new HashSet<>(source.propertyNames()));
          assertEquals(answerId, source.path("answer_id").asString());
          assertEquals(citation, source.path("citation"));
          var content = request("GET", citation.path("content_url").asString(), null, null, null);
          assertEquals(200, content.statusCode());
          assertArrayEquals(original, content.body());
          assertEquals(ModelValues.sha256(original), ModelValues.sha256(content.body()));
          assertContentHeaders(content, original.length);
          assertTrue(content.headers().firstValue("Content-Range").isEmpty());
        }
        String contentUrl = citations.getFirst().path("content_url").asString();
        assertRange(contentUrl, original, "bytes=0-31", 0, 31);
        assertRange(contentUrl, original, "bytes=-7", original.length - 7, original.length - 1);
        assertRange(
            contentUrl,
            original,
            "bytes=" + (original.length - 7) + "-",
            original.length - 7,
            original.length - 1);
        var unsatisfiable =
            request("GET", contentUrl, null, null, "bytes=" + original.length + "-");
        assertEquals(416, unsatisfiable.statusCode());
        assertEquals(0, unsatisfiable.body().length);
        assertContentHeaders(unsatisfiable, 0);
        assertEquals(
            "bytes */" + original.length,
            unsatisfiable.headers().firstValue("Content-Range").orElseThrow());

        Set<String> physicalIds =
            assertAudioTrace(
                data, audio.publication(), answerId, Set.of(text.document(), document));
        assertEquals(
            List.of("/rerank", "/chat/completions"),
            answers.requests.stream().map(AnswerProtocolServer.Request::path).toList());
        var extraction =
            JSON.readTree(
                answers
                    .requests
                    .getLast()
                    .body()
                    .path("messages")
                    .get(1)
                    .path("content")
                    .asString());
        var modelTexts = new HashSet<String>();
        var modelIds = new HashSet<String>();
        extraction
            .path("evidence")
            .forEach(
                value -> {
                  modelTexts.add(value.path("text").asString());
                  modelIds.add(value.path("evidence_id").asString());
                });
        assertEquals(Set.of(CODE, BUDGET), modelTexts);
        assertEquals(physicalIds, modelIds);
        var searches =
            indexing.requests.stream()
                .filter(request -> request.path().endsWith("/entities/search"))
                .toList();
        assertFalse(searches.isEmpty());
        for (var search : searches) {
          String filter = search.body().path("filter").asString();
          assertTrue(filter.contains("document_id == \"" + document + "\""));
          assertFalse(filter.contains(text.document()));
        }
        // Private ACL fixture changes only the unreferenced selected text in this static temp DB.
        revokeSelectedText(data, text.document());
        assertEquals(
            audioPublication,
            evidence.snapshot(OWNER, audioScope, target).publications().getFirst());
        for (var citation : citations) {
          assertNotFound(citation.path("source_url").asString(), null);
          String url = citation.path("content_url").asString();
          assertNotFound(url, null);
          assertNotFound(url, "bytes=0-31");
          assertNotFound(url, "bytes=" + original.length + "-");
        }
        assertEquals(
            indexRequests,
            indexing.requests.size(),
            "Source reads must not query models or Milvus");
        assertEquals(
            answerRequests, answers.requests.size(), "Source reads must not regenerate answers");
        assertEquals(3, asr.requests.size(), "Source reads must not retranscribe");
      }
    }
  }

  private Document uploadAndIndex(String filename, byte[] content) throws Exception {
    var upload =
        json(
            "POST", "/v1/documents?filename=" + filename, content, "application/octet-stream", 202);
    String document = upload.path("document_id").asString();
    await("ingestions", upload.path("task_id").asString(), "parsed");
    var queued = json("POST", "/v1/documents/" + document + "/index", null, null, 202);
    var indexed = await("indexings", queued.path("task_id").asString(), "indexed");
    String publication = indexed.path("index_publication_id").asString();
    assertFalse(publication.isBlank());
    return new Document(document, upload.path("revision_id").asString(), publication);
  }

  private static void assertTypedCitation(
      JsonNode citation,
      String answerId,
      Document audio,
      String compilerRevision,
      byte[] original) {
    assertEquals(
        Set.of(
            "number",
            "kind",
            "document_id",
            "revision_id",
            "source_sha256",
            "parser_revision",
            "filename",
            "media_type",
            "start_ms",
            "end_ms",
            "quote",
            "quote_sha256",
            "text_origin",
            "time_precision",
            "source_url",
            "content_url"),
        new HashSet<>(citation.propertyNames()));
    assertEquals("audio_span", citation.path("kind").asString());
    assertEquals(audio.document(), citation.path("document_id").asString());
    assertEquals(audio.revision(), citation.path("revision_id").asString());
    assertEquals(ModelValues.sha256(original), citation.path("source_sha256").asString());
    assertEquals(compilerRevision, citation.path("parser_revision").asString());
    assertEquals("meeting.wav", citation.path("filename").asString());
    assertEquals("audio/wav", citation.path("media_type").asString());
    assertEquals("machine_asr", citation.path("text_origin").asString());
    assertEquals("server_chunk", citation.path("time_precision").asString());
    String quote = citation.path("quote").asString();
    assertFalse(quote.isBlank());
    assertEquals(
        ModelValues.sha256(quote.getBytes(StandardCharsets.UTF_8)),
        citation.path("quote_sha256").asString());
    assertEquals(
        "/v1/audio-sources/" + answerId + "/" + citation.path("number").asInt(),
        citation.path("source_url").asString());
    assertEquals(
        citation.path("source_url").asString() + "/content",
        citation.path("content_url").asString());
    for (String absent :
        List.of("page", "start", "end", "confidence", "projection_generation_id")) {
      assertFalse(citation.has(absent));
    }
  }

  private static Set<String> assertAudioTrace(
      Path data, String publication, String answerId, Set<String> selected) throws Exception {
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + data.resolve("java-library.db"), config.toProperties())) {
      for (var expected :
          Map.of(
                  "audio_compilations",
                  1,
                  "audio_spans",
                  3,
                  "audio_publication_entries",
                  2,
                  "query_trace_documents",
                  2,
                  "audio_trace_evidence",
                  2,
                  "query_trace_evidence",
                  0,
                  "image_trace_evidence",
                  0)
              .entrySet()) {
        try (var query = connection.createStatement();
            var rows = query.executeQuery("SELECT COUNT(*) FROM " + expected.getKey())) {
          assertTrue(rows.next());
          assertEquals(expected.getValue().intValue(), rows.getInt(1), expected.getKey());
        }
      }
      try (var query =
          connection.prepareStatement(
              "SELECT p.document_id FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id WHERE q.trace_id=?")) {
        query.setString(1, answerId);
        try (var rows = query.executeQuery()) {
          var documents = new HashSet<String>();
          while (rows.next()) {
            documents.add(rows.getString(1));
          }
          assertEquals(selected, documents);
        }
      }
      try (var query =
          connection.prepareStatement(
              "SELECT t.physical_segment_id,s.ordinal,s.index_ordinal,t.start_ms,t.end_ms FROM audio_trace_evidence t JOIN audio_spans s ON s.id=t.audio_span_id JOIN audio_publication_entries e ON e.publication_id=t.publication_id AND e.audio_span_id=t.audio_span_id AND e.physical_segment_id=t.physical_segment_id WHERE t.trace_id=? AND t.publication_id=? ORDER BY s.ordinal")) {
        query.setString(1, answerId);
        query.setString(2, publication);
        try (var rows = query.executeQuery()) {
          var ids = new HashSet<String>();
          for (int index = 0; index < 2; index++) {
            assertTrue(rows.next());
            assertEquals(index * 2, rows.getInt("ordinal"));
            assertEquals(index, rows.getInt("index_ordinal"));
            assertEquals(index * 2000, rows.getLong("start_ms"));
            assertEquals(index == 0 ? 1000 : 2001, rows.getLong("end_ms"));
            assertTrue(ids.add(rows.getString("physical_segment_id")));
          }
          assertFalse(rows.next());
          return ids;
        }
      }
    }
  }

  private static void revokeSelectedText(Path data, String document) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + data.resolve("java-library.db"));
        var statement =
            connection.prepareStatement(
                "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
      statement.setString(1, document);
      statement.setString(2, OWNER.principalId());
      assertEquals(1, statement.executeUpdate());
    }
  }

  private void assertRange(String url, byte[] original, String range, int start, int end)
      throws Exception {
    var response = request("GET", url, null, null, range);
    assertEquals(206, response.statusCode());
    assertArrayEquals(Arrays.copyOfRange(original, start, end + 1), response.body());
    assertContentHeaders(response, end - start + 1);
    assertEquals(
        "bytes " + start + "-" + end + "/" + original.length,
        response.headers().firstValue("Content-Range").orElseThrow());
  }

  private static void assertContentHeaders(HttpResponse<byte[]> response, int length) {
    assertEquals("audio/wav", response.headers().firstValue("Content-Type").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
    assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElseThrow());
    assertEquals(length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
  }

  private void assertNotFound(String url, String range) throws Exception {
    var response = request("GET", url, null, null, range);
    assertEquals(404, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    assertTrue(response.headers().firstValue("Content-Range").isEmpty());
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
    return fail("Synthetic audio answer publication task did not finish");
  }

  private JsonNode json(String method, String path, byte[] body, String type, int status)
      throws Exception {
    var response = request(method, path, body, type, null);
    assertEquals(
        status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private HttpResponse<byte[]> request(
      String method, String path, byte[] body, String type, String range) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("X-Workspace-Id", OWNER.workspaceId())
            .header("X-Principal-Id", OWNER.principalId())
            .header("Origin", base);
    if (type != null) {
      request.header("Content-Type", type);
    }
    if (range != null) {
      request.header("Range", range);
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
        "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nsynthetic-answers-asr\r\n",
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

  private record Document(String document, String revision, String publication) {}

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
