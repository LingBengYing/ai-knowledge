package com.evidence.rag.web;

import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.base;
import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.publication;
import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.AnswersSettings;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.QueryPreparationService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ImageOcr;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplemental HTTP continuation/query/source evidence, not an original product RED. All production
 * HTTP, preparation, embedding clients, projection, authority and answer services execute. Only
 * query WAV decoding and empty query OCR are explicit synthetic local adapters; model responses are
 * loopback fixtures. This does not replace the separately executed native suites or prove model
 * quality. Existing receipt-seed fixtures and assertions remain unchanged.
 */
class ReindexVectorContinuationQueryHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WORKSPACE = "org-main";
  private static final String AUDIO_FACT = "Synthetic final room budget is 700 USD.";
  private static final String IMAGE_FACT = "Project A's budget is 650 USD.";
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"image", "audio"})
  void inheritedDenseQueryCitesTheNewPublicationAndRestartReopensTypedSourceWithoutModels(
      String route) throws Exception {
    var fixture = new ReindexVectorContinuationHttpFixture(directory);
    try (var storage = new ReindexVectorContinuationHttpFixture.Remote();
        var remote = new QueryRemote(storage);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      JsonNode citation;
      byte[] original;
      String answerId;
      String currentPublication;
      String document;
      String expectedPhysical;
      try (var app = start(remote)) {
        var seed = fixture.seed(app, http, route);
        document = seed.document();
        var originalMetadata =
            request(
                http, base(app), "GET", "/v1/documents/" + document + "/original", null, null, 200);
        original = bytes(http, base(app), originalMetadata.path("content_url").asString());
        assertEquals(seed.publication().sourceSha256(), ModelValues.sha256(original));
        var origin = origin(app, seed, route);
        remote.selectedPhysical = origin.vectorPhysical();
        remote.expectedDocument = document;
        remote.expectedGeneration = seed.vectorGeneration();
        remote.expectedOriginal = original;
        remote.expectedRoute = route;
        remote.expectedQuestion =
            route.equals("image")
                ? "What is Project A's budget?"
                : "What is Synthetic final room budget?";
        var task =
            request(
                http,
                base(app),
                "POST",
                "/v1/documents/" + document + "/reindex",
                JSON.writeValueAsBytes(
                    Map.of("base_publication_id", seed.publication().publicationId())),
                "application/json",
                202);
        int beforeRebuild = remote.calls.size();
        try (var operation = app.getBean(SqliteAuthorityStore.class).operationGate().enter()) {
          var processor = app.getBean(IndexingTaskProcessor.class);
          var claim = processor.claim().orElseThrow();
          assertEquals(task.path("task_id").asString(), claim.jobId());
          processor.process(claim);
        }
        var complete =
            request(
                http,
                base(app),
                "GET",
                "/v1/indexings/" + task.path("task_id").asString(),
                null,
                null,
                200);
        assertEquals("indexed", complete.path("state").asString(), complete.toString());
        var active = publication(app, document);
        currentPublication = active.publicationId();
        assertNotEquals(seed.publication().publicationId(), currentPublication);
        assertEquals(seed.publication().sourceRevisionId(), active.sourceRevisionId());
        expectedPhysical =
            RetrievalProjection.physicalSegmentId(
                active.projectionGenerationId(), origin.evidence());
        assertNotEquals(origin.basePhysical(), expectedPhysical);
        assertEquals(1, remote.countSince(beforeRebuild, "/embeddings"));
        assertEquals(0, remote.countSince(beforeRebuild, "/chat/completions"));
        assertEquals(0, remote.audioEmbeddings.size());
        assertEquals(0, remote.imageEmbeddings.size());
        assertEquals(0, remote.transcriptions.size());
        assertEquals(0, app.getBean(SyntheticDecoder.class).decodes.get());
        var query =
            JSON.writeValueAsBytes(
                Map.of(
                    "question",
                    remote.expectedQuestion,
                    "mode",
                    route,
                    "document_ids",
                    List.of(document),
                    "attachments",
                    List.of(
                        Map.of(
                            "filename", route.equals("image") ? "query.png" : "query.wav",
                            "media_type", route.equals("image") ? "image/png" : "audio/wav",
                            "content_base64", Base64.getEncoder().encodeToString(original)))));
        int beforeQuery = remote.calls.size();
        var envelope =
            request(
                http, base(app), "POST", "/v1/attachment-answers", query, "application/json", 200);
        var result = envelope.path("result");
        assertEquals("answered", result.path("status").asString(), envelope.toString());
        assertEquals(1, result.path("citations").size(), result.toString());
        assertEquals(1, envelope.path("query_attachments").size());
        citation = result.path("citations").get(0);
        answerId = result.path("answer_id").asString();
        assertEquals(document, citation.path("document_id").asString());
        assertEquals(active.sourceRevisionId(), citation.path("revision_id").asString());
        assertEquals(ModelValues.sha256(original), citation.path("source_sha256").asString());
        assertEquals(0, remote.countSince(beforeQuery, "/embeddings", false));
        if (route.equals("image")) {
          assertEquals("image_region", citation.path("kind").asString());
          assertEquals(1200, citation.path("width").asInt());
          assertEquals(180, citation.path("height").asInt());
          assertTrue(result.path("answer").asString().contains("650"));
          assertEquals(1, remote.imageEmbeddings.size());
          assertArrayEquals(original, remote.imageEmbeddings.getFirst());
          assertEquals(0, remote.audioEmbeddings.size());
          assertEquals(0, remote.transcriptions.size());
          assertEquals(4, remote.countSince(beforeQuery, "/chat/completions"));
          assertEquals(0, remote.countSince(beforeQuery, "/rerank"));
          assertEquals(1, app.getBean(SyntheticDecoder.class).ocrReads.get());
          assertEquals(0, app.getBean(SyntheticDecoder.class).decodes.get());
          assertEquals(List.of(IMAGE_FACT), remote.proposedFacts);
          assertEquals(1, remote.verifications.get());
        } else {
          assertEquals("audio_span", citation.path("kind").asString());
          assertEquals(2000, citation.path("start_ms").asLong());
          assertEquals(3000, citation.path("end_ms").asLong());
          assertEquals("machine_asr", citation.path("text_origin").asString());
          assertEquals("server_chunk", citation.path("time_precision").asString());
          assertEquals(
              AUDIO_FACT.substring(0, AUDIO_FACT.length() - 1), citation.path("quote").asString());
          assertEquals(0, remote.imageEmbeddings.size());
          assertEquals(3, remote.audioEmbeddings.size());
          assertEquals(3, remote.transcriptions.size());
          for (int ordinal = 0; ordinal < 3; ordinal++) {
            byte[] expected =
                wave(
                    Arrays.copyOfRange(original, 44 + ordinal * 32000, 44 + (ordinal + 1) * 32000));
            assertArrayEquals(expected, remote.audioEmbeddings.get(ordinal));
            assertArrayEquals(expected, remote.transcriptions.get(ordinal));
          }
          assertEquals(1, app.getBean(SyntheticDecoder.class).decodes.get());
          assertEquals(0, app.getBean(SyntheticDecoder.class).ocrReads.get());
          assertEquals(1, remote.countSince(beforeQuery, "/chat/completions"));
          assertEquals(1, remote.countSince(beforeQuery, "/rerank"));
          assertEquals(List.of(AUDIO_FACT), remote.extractedTexts);
        }
        assertEquals(route.equals("image") ? 1 : 3, remote.searches.size());
        for (var search : remote.searches) {
          assertEquals("dense", search.path("annsField").asString());
          assertEquals(
              "java_" + route + "_continuation_fixture", search.path("collectionName").asString());
          String filter = search.path("filter").asString();
          assertTrue(filter.contains(document) && filter.contains(seed.vectorGeneration()));
          assertFalse(filter.contains(active.projectionGenerationId()));
        }
        assertEquals(
            java.util.Collections.nCopies(route.equals("image") ? 1 : 3, origin.vectorPhysical()),
            remote.returnedPhysical);
        assertTrace(app, route, answerId, currentPublication, expectedPhysical, origin.evidence());
        assertEquals(
            citation,
            request(http, base(app), "GET", citation.path("source_url").asString(), null, null, 200)
                .path("citation"));
        assertArrayEquals(
            original, bytes(http, base(app), citation.path("content_url").asString()));
        assertTrue(remote.failures.isEmpty(), remote.failures.toString());
        assertFalse(
            Files.exists(directory.resolve("native-called")),
            "Default evidence never executes native placeholders");
      }
      int calls = remote.calls.size();
      int storageCalls = storage.requests.size();
      try (var app = start(remote)) {
        assertEquals(currentPublication, publication(app, document).publicationId());
        assertEquals(
            citation,
            request(http, base(app), "GET", citation.path("source_url").asString(), null, null, 200)
                .path("citation"));
        assertArrayEquals(
            original, bytes(http, base(app), citation.path("content_url").asString()));
        assertTrace(app, route, answerId, currentPublication, expectedPhysical, null);
        assertEquals(0, app.getBean(SyntheticDecoder.class).decodes.get());
        assertEquals(0, app.getBean(SyntheticDecoder.class).ocrReads.get());
        assertEquals(
            calls, remote.calls.size(), "Source/read restart makes no model or projection call");
        assertEquals(storageCalls, storage.requests.size());
        assertTrue(remote.failures.isEmpty(), remote.failures.toString());
      }
    }
  }

  private record Origin(String evidence, String basePhysical, String vectorPhysical) {}

  private static Origin origin(
      ConfigurableApplicationContext app,
      ReindexVectorContinuationHttpFixture.Seed seed,
      String route) {
    return app.getBean(SqliteAuthorityStore.class)
        .transaction(
            () -> {
              if (route.equals("image")) {
                var receipt =
                    app.getBean(ImageVectorRepository.class)
                        .allBindings("org-main", seed.publication())
                        .getFirst()
                        .origin();
                return new Origin(
                    receipt.imageEvidenceId(),
                    receipt.basePhysicalSegmentId(),
                    receipt.vectorPhysicalSegmentId());
              }
              var receipt =
                  app.getBean(AudioVectorRepository.class)
                      .allBindings("org-main", seed.publication())
                      .getFirst()
                      .origin();
              assertEquals(
                  List.of(0, 2), receipt.entries().stream().map(entry -> entry.ordinal()).toList());
              var tail = receipt.entries().getLast();
              assertEquals(32000, tail.startSample());
              assertEquals(48000, tail.endSample());
              return new Origin(
                  tail.audioEvidenceId(),
                  tail.basePhysicalSegmentId(),
                  tail.vectorPhysicalSegmentId());
            });
  }

  private static void assertTrace(
      ConfigurableApplicationContext app,
      String route,
      String answer,
      String publication,
      String physical,
      String evidence)
      throws Exception {
    String idColumn = route.equals("image") ? "image_evidence_id" : "audio_span_id";
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + app.getBean(SqliteAuthorityStore.class).libraryPath());
        var query =
            connection.prepareStatement(
                "SELECT publication_id,physical_segment_id,"
                    + idColumn
                    + " FROM "
                    + route
                    + "_trace_evidence WHERE trace_id=?")) {
      query.setString(1, answer);
      try (var row = query.executeQuery()) {
        assertTrue(row.next());
        assertEquals(publication, row.getString(1));
        assertEquals(physical, row.getString(2));
        if (evidence != null) {
          assertEquals(evidence, row.getString(3));
        }
        assertFalse(row.next());
      }
    }
  }

  private static byte[] bytes(HttpClient http, String base, String path) throws Exception {
    assertTrue(path.startsWith("/v1/"));
    var result =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Origin", base)
                .header("X-Workspace-Id", "org-main")
                .header("X-Principal-Id", "owner")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, result.statusCode());
    return result.body();
  }

  private ConfigurableApplicationContext start(QueryRemote remote) throws Exception {
    Path stub = directory.resolve("native-stub");
    Files.writeString(
        stub, "#!/bin/sh\nprintf called > '" + directory.resolve("native-called") + "'\nexit 91\n");
    assertTrue(stub.toFile().setExecutable(true));
    String decoderRevision = SyntheticDecoder.REVISION;
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var values = new LinkedHashMap<String, Object>();
    for (String role : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      values.put("RAG_" + role + "_BASE_URL", remote.endpoint().toString());
      values.put("RAG_" + role + "_MODEL", "fixture-text");
      values.put("RAG_" + role + "_API_KEY", "synthetic-fixture-credential");
    }
    values.put("RAG_EMBEDDING_DIMENSIONS", 2);
    values.put("RAG_EMBEDDING_REVISION", "fixture-text-v1");
    values.put("RAG_MILVUS_ENDPOINT", remote.endpoint().toString());
    values.put("RAG_MILVUS_TOKEN", "synthetic-fixture-credential");
    values.put("RAG_MILVUS_COLLECTION", "java_text_continuation_fixture");
    values.put("RAG_WORKSPACE_ID", WORKSPACE);
    values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", true);
    values.put("RAG_TEXT_DEADLINE_MS", 10000);
    values.put("server.port", 0);
    values.put("server.address", "127.0.0.1");
    values.put("rag.environment", "test");
    values.put("rag.auth-mode", "development_headers");
    values.put("rag.workspace-id", WORKSPACE);
    values.put("rag.data-directory", directory.resolve("data"));
    for (String module :
        List.of(
            "ingestion",
            "indexing",
            "answers",
            "visual",
            "audio",
            "video",
            "image-ocr",
            "query-attachments",
            "image-embedding",
            "audio-embedding")) {
      values.put("rag." + module + ".enabled", true);
    }
    for (String prefix :
        List.of(
            "rag.visual",
            "rag.audio",
            "rag.video.asr",
            "rag.video.vision",
            "rag.query-attachments.ranking",
            "rag.image-embedding",
            "rag.audio-embedding")) {
      values.put(prefix + ".base-url", remote.endpoint().toString());
      values.put(prefix + ".model", "fixture-media");
      values.put(prefix + ".api-key", "synthetic-fixture-credential");
      values.put(prefix + ".allow-loopback-http", true);
    }
    for (String prefix : List.of("rag.audio", "rag.video")) {
      values.put(prefix + ".ffmpeg-executable", stub.toRealPath().toString());
      values.put(prefix + ".ffprobe-executable", stub.toRealPath().toString());
    }
    values.put("rag.audio.chunk-seconds", 1);
    values.put("rag.image-ocr.executable", stub.toRealPath().toString());
    values.put("rag.image-ocr.revision", "synthetic-continuation-ocr-v1");
    values.put("rag.image-ocr.language", "eng");
    values.put("rag.audio-embedding.decoder-revision", decoderRevision);
    for (String route : List.of("image", "audio")) {
      String prefix = "rag." + route + "-embedding";
      values.put(prefix + ".revision", "synthetic-continuation-" + route + "-v1");
      values.put(prefix + ".dimensions", 2);
      values.put(prefix + ".milvus.endpoint", remote.endpoint().toString());
      values.put(prefix + ".milvus.token", "synthetic-fixture-credential");
      values.put(prefix + ".milvus.collection", "java_" + route + "_continuation_fixture");
      values.put(prefix + ".milvus.allow-loopback-http", true);
    }
    values.put("rag.indexing.timeout-ms", 15000);
    values.put("rag.query-attachments.ranking.model", "fixture-ranking");
    var application = new SpringApplication(RagApplication.class, SyntheticInputs.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(values);
    var args =
        values.entrySet().stream()
            .filter(e -> e.getKey().startsWith("rag.") || e.getKey().startsWith("server."))
            .map(e -> "--" + e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
    var app = application.run(args);
    app.getBean(IngestionJob.class).close();
    app.getBean(IndexingJob.class).close();
    return app;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SyntheticInputs {
    @Bean
    @Primary
    SyntheticDecoder syntheticQueryDecoder() {
      return new SyntheticDecoder();
    }

    @Bean
    @Primary
    QueryPreparationService syntheticNativeQueryPreparation(
        VisionModels vision,
        SyntheticDecoder inputs,
        AudioCompilationService audio,
        VideoCompilationService video,
        AnswersSettings limits) {
      ImageOcr emptyOcr =
          new ImageOcr() {
            @Override
            public String revision() {
              return "synthetic-query-empty-ocr-v1";
            }

            @Override
            public Optional<ParsedImage> read(VisualImage image) {
              inputs.ocrReads.incrementAndGet();
              assertEquals("image/png", image.mediaType());
              return Optional.empty();
            }
          };
      return new QueryPreparationService(
          vision, emptyOcr, audio, video, Duration.ofMillis(limits.timeoutMs()), true);
    }
  }

  static final class SyntheticDecoder implements AudioDecoder {
    static final String REVISION = "synthetic-query-wav-pcm-v1";
    final AtomicInteger decodes = new AtomicInteger();
    final AtomicInteger ocrReads = new AtomicInteger();

    @Override
    public String revision() {
      return REVISION;
    }

    @Override
    public DecodedAudio decode(String filename, String mime, byte[] source) {
      assertEquals("query.wav", filename);
      assertEquals("audio/wav", mime);
      assertEquals(96044, source.length);
      byte[] pcm = Arrays.copyOfRange(source, 44, source.length);
      assertArrayEquals(
          wave(pcm), source, "Synthetic adapter only accepts exact canonical fixture WAV");
      decodes.incrementAndGet();
      return new DecodedAudio(ModelValues.sha256(source), revision(), pcm);
    }

    @Override
    public void close() {}
  }

  private static byte[] wave(byte[] pcm) {
    var result = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    result.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
    result.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    result.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
    result
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(pcm.length)
        .put(pcm);
    return result.array();
  }

  /**
   * Records all real HTTP requests and forwards storage protocol to the existing strict fixture.
   */
  private static final class QueryRemote implements AutoCloseable {
    private static final Pattern SCOPE =
        Pattern.compile(
            "\\(document_id == \"([A-Za-z0-9._:-]+)\" && revision_id == \"([A-Za-z0-9._:-]+)\"\\)");
    private final ReindexVectorContinuationHttpFixture.Remote storage;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient forwarding =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Map<String, JsonNode>> rows = new ConcurrentHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<JsonNode> searches = new CopyOnWriteArrayList<>();
    final List<String> returnedPhysical = new CopyOnWriteArrayList<>();
    final List<byte[]> imageEmbeddings = new CopyOnWriteArrayList<>();
    final List<byte[]> audioEmbeddings = new CopyOnWriteArrayList<>();
    final List<byte[]> transcriptions = new CopyOnWriteArrayList<>();
    final List<String> proposedFacts = new CopyOnWriteArrayList<>();
    final List<String> extractedTexts = new CopyOnWriteArrayList<>();
    final List<String> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger verifications = new AtomicInteger();
    volatile String selectedPhysical;
    volatile String expectedDocument;
    volatile String expectedGeneration;
    volatile String expectedRoute;
    volatile String expectedQuestion;
    volatile byte[] expectedOriginal;

    private record Call(String path, JsonNode body) {}

    QueryRemote(ReindexVectorContinuationHttpFixture.Remote storage) throws IOException {
      this.storage = storage;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    long countSince(int offset, String path) {
      return calls.subList(offset, calls.size()).stream()
          .filter(call -> call.path().equals(path))
          .count();
    }

    long countSince(int offset, String path, boolean image) {
      return calls.subList(offset, calls.size()).stream()
          .filter(call -> call.path().equals(path) && call.body().path("input").isObject() == image)
          .count();
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        try {
          String path = exchange.getRequestURI().getPath();
          byte[] raw = exchange.getRequestBody().readAllBytes();
          var body =
              path.endsWith("audio/transcriptions") ? JSON.createObjectNode() : JSON.readTree(raw);
          calls.add(new Call(path, body));
          Object response;
          if (path.equals("/embeddings") && body.path("input").isObject()) {
            byte[] image = Base64.getDecoder().decode(body.path("input").path("image").asString());
            assertArrayEquals(expectedOriginal, image);
            imageEmbeddings.add(image);
            response =
                Map.of(
                    "object",
                    "list",
                    "model",
                    body.path("model").asString(),
                    "data",
                    List.of(
                        Map.of("object", "embedding", "index", 0, "embedding", List.of(1.0, 0.0))));
          } else if (path.endsWith(":embedContent")) {
            assertEquals("/v1beta/models/fixture-media:embedContent", path);
            assertEquals(
                "audio/wav",
                body.path("content")
                    .path("parts")
                    .get(0)
                    .path("inlineData")
                    .path("mimeType")
                    .asString());
            assertEquals(2, body.path("embedContentConfig").path("outputDimensionality").asInt());
            assertFalse(body.path("embedContentConfig").path("autoTruncate").asBoolean());
            audioEmbeddings.add(
                Base64.getDecoder()
                    .decode(
                        body.path("content")
                            .path("parts")
                            .get(0)
                            .path("inlineData")
                            .path("data")
                            .asString()));
            response = Map.of("embedding", Map.of("values", List.of(1.0, 0.0)));
          } else if (path.endsWith("audio/transcriptions")) {
            byte[] wav = multipartWav(raw, exchange.getRequestHeaders().getFirst("Content-Type"));
            transcriptions.add(wav);
            boolean silence = true;
            for (int i = 44; i < wav.length; i++) {
              if (wav[i] != 0) {
                silence = false;
                break;
              }
            }
            response = Map.of("text", silence ? "" : "Synthetic query audio.");
          } else if (path.equals("/chat/completions")) {
            response = chat(body);
          } else if (path.equals("/rerank")) {
            assertEquals(1, body.path("documents").size());
            assertEquals(AUDIO_FACT, body.path("documents").get(0).asString());
            response = Map.of("results", List.of(Map.of("index", 0, "relevance_score", 0.99)));
          } else if (path.endsWith("entities/search")) {
            response = Map.of("code", 0, "data", search(body));
          } else {
            if (path.endsWith("entities/upsert")) {
              var saved =
                  rows.computeIfAbsent(
                      body.path("collectionName").asString(), ignored -> new ConcurrentHashMap<>());
              for (var row : body.path("data")) {
                saved.put(row.path("id").asString(), row);
              }
            }
            var forwarded =
                forwarding.send(
                    HttpRequest.newBuilder(storage.endpoint().resolve(path))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, forwarded.statusCode());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, forwarded.body().length);
            exchange.getResponseBody().write(forwarded.body());
            return;
          }
          byte[] content = JSON.writeValueAsBytes(response);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, content.length);
          exchange.getResponseBody().write(content);
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          failures.add(stopped.getClass().getSimpleName());
          exchange.sendResponseHeaders(500, -1);
        } catch (AssertionError | RuntimeException failure) {
          failures.add(failure.toString());
          exchange.sendResponseHeaders(500, -1);
        }
      }
    }

    private Object chat(JsonNode body) throws IOException {
      Object value;
      if (body.path("model").asString().equals("fixture-ranking")) {
        var ranks = new ArrayList<Object>();
        for (var part : body.path("messages").get(1).path("content")) {
          if (part.has("text")) {
            var marker = JSON.readTree(part.path("text").asString());
            if (marker.path("role").asString().equals("query")) {
              assertEquals(expectedQuestion, marker.path("original_question").asString());
            }
            if (marker.path("role").asString().equals("authorized_candidate")) {
              ranks.add(Map.of("index", marker.path("index").asInt(), "score", 0.99));
            }
          }
        }
        assertEquals(1, ranks.size());
        value = Map.of("rankings", ranks);
      } else {
        var content = body.path("messages").get(1).path("content");
        var input =
            JSON.readTree(
                content.isString() ? content.asString() : content.get(0).path("text").asString());
        if (body.path("model").asString().equals("fixture-text")) {
          assertEquals(expectedQuestion, input.path("question").asString());
          assertEquals(1, input.path("evidence").size());
          var evidence = input.path("evidence").get(0);
          assertEquals(AUDIO_FACT, evidence.path("text").asString());
          extractedTexts.add(evidence.path("text").asString());
          value =
              Map.of(
                  "refused",
                  false,
                  "quotes",
                  List.of(
                      Map.of(
                          "evidence_id",
                          evidence.path("evidence_id").asString(),
                          "quote",
                          AUDIO_FACT)));
        } else {
          assertArrayEquals(expectedOriginal, imageInput(body));
          if (input.has("claims")) {
            assertEquals(expectedQuestion, input.path("question").asString());
            assertEquals(IMAGE_FACT, input.path("claims").get(0).path("claim").asString());
            verifications.incrementAndGet();
            value =
                Map.of("complete", true, "support", List.of(Map.of("index", 0, "supported", true)));
          } else if (input.has("question")) {
            assertEquals(expectedQuestion, input.path("question").asString());
            proposedFacts.add(IMAGE_FACT);
            value = Map.of("refused", false, "claims", List.of(IMAGE_FACT));
          } else {
            assertTrue(input.isObject() && input.isEmpty());
            value = Map.of("recall_text", "Synthetic query image.");
          }
        }
      }
      return Map.of(
          "choices",
          List.of(
              Map.of(
                  "index",
                  0,
                  "finish_reason",
                  "stop",
                  "message",
                  Map.of("role", "assistant", "content", JSON.writeValueAsString(value)))));
    }

    private List<Object> search(JsonNode body) {
      searches.add(body);
      assertEquals("dense", body.path("annsField").asString());
      assertEquals(
          "java_" + expectedRoute + "_continuation_fixture",
          body.path("collectionName").asString());
      var scope = new HashMap<String, String>();
      var matcher = SCOPE.matcher(body.path("filter").asString());
      while (matcher.find()) {
        scope.put(matcher.group(1), matcher.group(2));
      }
      assertEquals(Map.of(expectedDocument, expectedGeneration), scope);
      assertTrue(body.path("filter").asString().contains("workspace_id == \"org-main\""));
      var eligible =
          rows.get(body.path("collectionName").asString()).values().stream()
              .filter(
                  row ->
                      row.path("workspace_id").asString().equals("org-main")
                          && row.path("revision_id")
                              .asString()
                              .equals(scope.get(row.path("document_id").asString())))
              .toList();
      assertEquals(expectedRoute.equals("image") ? 1 : 2, eligible.size());
      var selected =
          eligible.stream()
              .filter(row -> row.path("id").asString().equals(selectedPhysical))
              .findFirst()
              .orElseThrow();
      assertEquals(expectedGeneration, selected.path("revision_id").asString());
      returnedPhysical.add(selected.path("id").asString());
      return List.of(
          Map.of(
              "id",
              selected.path("id").asString(),
              "workspace_id",
              "org-main",
              "document_id",
              expectedDocument,
              "revision_id",
              expectedGeneration,
              "distance",
              1.0));
    }

    private static byte[] imageInput(JsonNode body) {
      for (var part : body.path("messages").get(1).path("content")) {
        if (part.path("type").asString().equals("image_url")) {
          String data = part.path("image_url").path("url").asString();
          return Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
        }
      }
      throw new AssertionError("Original image bytes missing");
    }

    private static byte[] multipartWav(byte[] raw, String contentType) {
      String boundary = contentType.substring("multipart/form-data; boundary=".length());
      String[] parts =
          new String(raw, StandardCharsets.ISO_8859_1).split(Pattern.quote("--" + boundary), -1);
      assertEquals(4, parts.length);
      int start = parts[2].indexOf("\r\n\r\n") + 4;
      return parts[2].substring(start, parts[2].length() - 2).getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
      forwarding.close();
    }
  }
}
