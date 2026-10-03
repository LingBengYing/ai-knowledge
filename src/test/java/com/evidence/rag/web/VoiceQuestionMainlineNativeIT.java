package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import com.evidence.rag.worker.parser.ProcessTextParser;
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
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit native decode and Spring HTTP; all ASR, answer and Milvus calls use loopback fixtures.
 */
class VoiceQuestionMainlineNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "voice-owner");
  private static final String POLICY = "上海住宿上限为650元。";
  private static final String POLICY_QUOTE = "上海住宿上限为650元";
  private static final String QUESTION = "上海住宿上限是多少？";
  private static final List<String> TRANSCRIPTS = List.of(" 上海住宿上限", "", "是多少？ ");
  @TempDir Path directory;

  @Test
  void nativeVoicePreviewThenConfirmedTextAnswersOnlyFromTheSelectedLibrary() throws Exception {
    if (!"true".equals(System.getenv("RAG_VOICE_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native voice IT opt-in is required");
    }
    Path ffmpeg = configuredPath("RAG_VOICE_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VOICE_IT_FFPROBE");
    byte[] pcm = new byte[3 * 32000];
    var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < pcm.length / 2; index++) {
      samples.putShort((short) (index * 31 + 17));
    }
    byte[] original = AudioPcm.wav(pcm, 0, pcm.length);
    Path data = directory.resolve("voice-data");
    try (var answers = new AnswerProtocolServer();
        var asr = new AsrServer();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var context = application(answers).run(arguments(data, ffmpeg, ffprobe, asr))) {
      context.getBean(IngestionJob.class).close();
      context.getBean(IndexingJob.class).close();
      assertInstanceOf(ProcessAudioDecoder.class, context.getBean(AudioDecoder.class));
      assertInstanceOf(OpenAiCompatibleAudioModels.class, context.getBean(AudioModels.class));
      assertTrue(asr.requests.isEmpty(), "Startup must not transcribe");
      assertTrue(answers.requests.isEmpty(), "Startup must not query models or Milvus");
      String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      var capabilities = new HashSet<String>();
      json(client, base, "GET", "/v1/config", null, null, 200)
          .path("capabilities")
          .forEach(value -> capabilities.add(value.asString()));
      assertTrue(capabilities.contains("voice_questions"));
      assertFalse(capabilities.contains("query_attachments"));
      assertFalse(capabilities.contains("visual_answers"));
      String document = publishPolicy(client, base, context, answers);
      Map<String, Long> before = counts(data);
      assertEquals(1L, before.get("documents"));
      assertEquals(0L, before.get("query_traces"));
      int libraryModelCalls = answers.requests.size();
      byte[] body =
          JSON.writeValueAsBytes(
              Map.of(
                  "filename", "question.wav",
                  "media_type", "audio/wav",
                  "content_base64", Base64.getEncoder().encodeToString(original)));
      var preview =
          json(client, base, "POST", "/v1/voice-questions", body, "application/json", 200);
      assertEquals(
          Set.of(
              "transcript",
              "transcript_sha256",
              "source_sha256",
              "decoder_revision",
              "model_revision",
              "compiler_revision",
              "duration_ms",
              "policy_revision"),
          new HashSet<>(preview.propertyNames()));
      String transcript = String.join("\n", TRANSCRIPTS);
      assertEquals(transcript, preview.path("transcript").asString());
      assertEquals(
          ModelValues.sha256(transcript.getBytes(StandardCharsets.UTF_8)),
          preview.path("transcript_sha256").asString());
      assertEquals(ModelValues.sha256(original), preview.path("source_sha256").asString());
      assertEquals(
          context.getBean(AudioDecoder.class).revision(),
          preview.path("decoder_revision").asString());
      assertEquals(
          context.getBean(AudioModels.class).revision(), preview.path("model_revision").asString());
      assertEquals(
          context.getBean(AudioCompilationService.class).revision(),
          preview.path("compiler_revision").asString());
      assertEquals(3000, preview.path("duration_ms").asLong());
      assertEquals("java-voice-question-v1", preview.path("policy_revision").asString());
      assertEquals(3, asr.requests.size());
      for (int ordinal = 0; ordinal < 3; ordinal++) {
        assertArrayEquals(
            AudioPcm.wav(pcm, ordinal * 32000, (ordinal + 1) * 32000),
            wavPayload(asr.requests.get(ordinal)));
      }
      assertEquals(
          before, counts(data), "Voice preparation must not persist media, text, audit or traces");
      assertEquals(
          libraryModelCalls, answers.requests.size(), "Voice preview must not read library models");
      // Simulates the user's explicit edit and confirmation, through the unchanged answer endpoint.
      var answered =
          json(
              client,
              base,
              "POST",
              "/v1/answers",
              JSON.writeValueAsBytes(
                  Map.of("question", QUESTION, "document_ids", List.of(document))),
              "application/json",
              200);
      assertEquals("answered", answered.path("status").asString(), answered.toString());
      assertTrue(answered.path("answer").asString().contains("650"));
      assertEquals(1, answered.path("citations").size());
      var citation = answered.path("citations").get(0);
      assertEquals(document, citation.path("document_id").asString());
      assertEquals(POLICY_QUOTE, citation.path("quote").asString());
      String source = citation.path("source_url").asString();
      assertTrue(source.startsWith("/v1/sources/"));
      var recalled = json(client, base, "GET", source, null, null, 200);
      assertEquals(POLICY_QUOTE, recalled.path("citation").path("quote").asString());
      assertEquals(3, asr.requests.size(), "Confirmation and source reads must not retranscribe");
      var after = counts(data);
      assertEquals(before.get("documents"), after.get("documents"));
      assertEquals(before.get("management_audit"), after.get("management_audit"));
      assertEquals(1L, after.get("query_traces"));
      assertEquals(1L, after.get("query_trace_documents"));
      assertEquals(1L, after.get("query_trace_evidence"));
      for (String table :
          List.of(
              "audio_compilations",
              "audio_spans",
              "query_trace_preparations",
              "query_trace_attachments")) {
        assertEquals(0L, after.get(table), table);
      }
      assertFalse(answered.toString().contains("question.wav"));
    }
  }

  private static SpringApplication application(AnswerProtocolServer answers) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(new LinkedHashMap<>(answers.environment()));
    return application;
  }

  private static String[] arguments(Path data, Path ffmpeg, Path ffprobe, AsrServer asr) {
    return new String[] {
      "--server.port=0",
      "--server.address=127.0.0.1",
      "--rag.environment=test",
      "--rag.workspace-id=org-main",
      "--rag.auth-mode=development_headers",
      "--rag.data-directory=" + data,
      "--rag.ingestion.enabled=true",
      "--rag.indexing.enabled=true",
      "--rag.answers.enabled=true",
      "--rag.audio.enabled=true",
      "--rag.voice-questions.enabled=true",
      "--rag.visual.enabled=false",
      "--rag.video.enabled=false",
      "--rag.query-attachments.enabled=false",
      "--rag.image-ocr.enabled=false",
      "--rag.pdf-ocr.enabled=false",
      "--rag.synopsis.enabled=false",
      "--rag.document-removal.enabled=false",
      "--rag.audio.ffmpeg-executable=" + ffmpeg,
      "--rag.audio.ffprobe-executable=" + ffprobe,
      "--rag.audio.decode-deadline-ms=10000",
      "--rag.audio.chunk-seconds=1",
      "--rag.audio.compilation-budget-ms=30000",
      "--rag.audio.base-url=" + asr.endpoint(),
      "--rag.audio.model=synthetic-voice-asr",
      "--rag.audio.api-key=synthetic-voice-asr-credential",
      "--rag.audio.deadline-ms=5000",
      "--rag.audio.max-response-bytes=65536",
      "--rag.audio.allow-loopback-http=true",
      "--rag.voice-questions.processing-timeout-ms=30000",
      "--rag.answers.timeout-ms=15000"
    };
  }

  private static String publishPolicy(
      HttpClient client,
      String base,
      ConfigurableApplicationContext context,
      AnswerProtocolServer remote)
      throws Exception {
    var uploaded =
        json(
            client,
            base,
            "POST",
            "/v1/documents?filename=policy.txt",
            POLICY.getBytes(StandardCharsets.UTF_8),
            "application/octet-stream",
            202);
    String document = uploaded.path("document_id").asString();
    var ingestion = context.getBean(IngestionService.class);
    var parse = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertEquals(document, parse.documentId());
    try (var parser = new ProcessTextParser(Duration.ofSeconds(10))) {
      assertTrue(
          ingestion.completeIngestion(
              parse, parser.parse(parse.filename(), parse.mimeType(), parse.content())));
    }
    var settings = context.getBean(TextAdapterSettings.class);
    var models = context.getBean(TextModels.class);
    var target =
        new IndexTarget(
            settings.projection().embeddingIdentity(),
            settings.projection().identity(),
            models.revision(),
            settings.projection().dimension());
    var indexing = context.getBean(IndexingService.class);
    indexing.createIndexing(OWNER, document, target);
    var claim = indexing.claimIndexing(OWNER.workspaceId()).orElseThrow();
    var entries =
        claim.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), item.evidenceId()),
                        OWNER.workspaceId(),
                        document,
                        claim.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    var digests = new TreeMap<String, String>();
    entries.forEach(
        entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            OWNER.workspaceId(), document, claim.projectionGenerationId(), digests);
    assertTrue(
        indexing.completeIndexing(
            claim,
            digests,
            new VerifiedRevision(target.projectionIdentity(), manifest.sha256(), entries.size())));
    remote.install(entries);
    return document;
  }

  private static JsonNode json(
      HttpClient client,
      String base,
      String method,
      String path,
      byte[] body,
      String contentType,
      int status)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Origin", base)
            .header("X-Workspace-Id", OWNER.workspaceId())
            .header("X-Principal-Id", OWNER.principalId());
    if (contentType != null) {
      request.header("Content-Type", contentType);
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
            HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static Map<String, Long> counts(Path data) throws Exception {
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    var counts = new LinkedHashMap<String, Long>();
    try (var connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + data.resolve("java-library.db"), config.toProperties())) {
      for (String table :
          List.of(
              "documents",
              "corpus_documents",
              "corpus_revisions",
              "corpus_segments",
              "ingestion_jobs",
              "indexing_jobs",
              "index_publications",
              "management_audit",
              "query_traces",
              "query_trace_documents",
              "query_trace_evidence",
              "audio_compilations",
              "audio_spans",
              "query_trace_preparations",
              "query_trace_attachments")) {
        try (var query = connection.createStatement();
            var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          counts.put(table, rows.getLong(1));
        }
      }
    }
    return counts;
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
        "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nsynthetic-voice-asr\r\n",
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
    if (value == null
        || value.isBlank()
        || !Path.of(value).isAbsolute()
        || !Files.isExecutable(Path.of(value))) {
      throw new IllegalArgumentException("Explicit native voice executable paths are required");
    }
    return Path.of(value);
  }

  private record AsrRequest(String contentType, byte[] body) {}

  private static final class AsrServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<AsrRequest> requests = new CopyOnWriteArrayList<>();

    AsrServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/audio/transcriptions", this::reply);
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void reply(HttpExchange exchange) throws IOException {
      try (exchange) {
        if (!"POST".equals(exchange.getRequestMethod())
            || !"/v1/audio/transcriptions".equals(exchange.getRequestURI().getPath())
            || !"Bearer synthetic-voice-asr-credential"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        byte[] original = exchange.getRequestBody().readNBytes(1_048_577);
        if (original.length > 1_048_576) {
          exchange.sendResponseHeaders(413, -1);
          return;
        }
        int ordinal = requests.size();
        requests.add(
            new AsrRequest(exchange.getRequestHeaders().getFirst("Content-Type"), original));
        if (ordinal >= TRANSCRIPTS.size()) {
          exchange.sendResponseHeaders(503, -1);
          return;
        }
        byte[] body = JSON.writeValueAsBytes(Map.of("text", TRANSCRIPTS.get(ordinal)));
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
