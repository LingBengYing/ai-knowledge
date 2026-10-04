package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.AudioEmbeddingSettings;
import com.evidence.rag.config.ImageEmbeddingSettings;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.IngestionService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual application/HTTP/SQLite and text worker; media compilation and vectors are synthetic
 * seeds. Independent receipts are sealed only after real loopback projection upsert and full
 * verification. This is an entry-contract regression, not native decoder or media embedding quality
 * validation.
 */
final class ReindexVectorContinuationHttpFixture {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WORKSPACE = "org-main";
  private static final String FACT = "Synthetic room budget is 650 USD.";
  final Path directory;

  ReindexVectorContinuationHttpFixture(Path directory) {
    this.directory = directory;
  }

  record Seed(
      String document,
      String initialTask,
      PublicationVersion publication,
      String vectorGeneration) {}

  Seed seed(ConfigurableApplicationContext app, HttpClient http, String route) throws Exception {
    boolean image = route.equals("image");
    byte[] source = image ? ImageMainlineHttpTest.image() : wav();
    String name = image ? "continuation.png" : "continuation.wav";
    var uploaded =
        request(
            http,
            base(app),
            "POST",
            "/v1/documents?filename=" + name,
            source,
            "application/octet-stream",
            202);
    String document = uploaded.path("document_id").asString();
    var ingestion = app.getBean(IngestionService.class);
    var ingest = ingestion.claimIngestion(WORKSPACE).orElseThrow();
    assertEquals(document, ingest.documentId());
    assertEquals(image ? "image/png" : "audio/wav", ingest.mimeType());
    AudioCompilation audio = null;
    if (image) {
      assertTrue(
          ingestion.completeVisualIngestion(
              ingest, new ImageRecall(FACT, app.getBean(VisionModels.class).revision())));
    } else {
      audio =
          new AudioCompilation(
              ModelValues.sha256(source),
              app.getBean(AudioDecoder.class).revision(),
              app.getBean(AudioModels.class).revision(),
              app.getBean(AudioCompilationService.class).revision(),
              3000,
              List.of(
                  new AudioTranscriptSpan(0, 0, 1000, FACT),
                  new AudioTranscriptSpan(1, 1000, 2000, ""),
                  new AudioTranscriptSpan(
                      2, 2000, 3000, "Synthetic final room budget is 700 USD.")));
      assertEquals(audio.compilerRevision(), ingest.parserRevision());
      assertTrue(ingestion.completeAudioIngestion(ingest, audio));
    }
    var queued =
        request(http, base(app), "POST", "/v1/documents/" + document + "/index", null, null, 202);
    String task = queued.path("task_id").asString();
    var store = app.getBean(SqliteAuthorityStore.class);
    IndexClaim claim;
    try (var operation = store.operationGate().enter()) {
      var processor = app.getBean(IndexingTaskProcessor.class);
      claim = processor.claim().orElseThrow();
      assertEquals(task, claim.jobId());
      processor.process(claim);
    }
    var indexed = request(http, base(app), "GET", "/v1/indexings/" + task, null, null, 200);
    assertEquals("indexed", indexed.path("state").asString(), indexed.toString());
    var publication = publication(app, document);
    assertEquals(indexed.path("index_publication_id").asString(), publication.publicationId());
    String generation = UUID.randomUUID().toString();
    String receiptId = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    if (image) {
      var settings = app.getBean(ImageEmbeddingSettings.class);
      String evidenceId = claim.items().getFirst().evidenceId();
      String physical = RetrievalProjection.physicalSegmentId(generation, evidenceId);
      var entry =
          new RetrievalProjection.Entry(
              physical,
              WORKSPACE,
              document,
              generation,
              publication.sourceSha256(),
              List.of(1.0, 0.0));
      String entrySha = RetrievalProjection.entryDigest(entry);
      var manifest =
          new RetrievalProjection.RevisionManifest(
              WORKSPACE, document, generation, Map.of(physical, entrySha));
      verifySeed(settings.projection(), List.of(entry), manifest);
      var receipt =
          new ImageVectorPublication(
              receiptId,
              publication,
              evidenceId,
              RetrievalProjection.physicalSegmentId(
                  publication.projectionGenerationId(), evidenceId),
              generation,
              physical,
              settings.target(),
              entrySha,
              manifest.sha256(),
              now);
      store.transaction(
          () -> {
            app.getBean(ImageVectorRepository.class).insert(receipt);
            return null;
          });
    } else {
      var settings = app.getBean(AudioEmbeddingSettings.class);
      var items = new ArrayList<RetrievalProjection.Entry>();
      var saved = new ArrayList<AudioVectorEntry>();
      var digests = new TreeMap<String, String>();
      byte[] pcm = Arrays.copyOfRange(source, 44, source.length);
      for (var evidence : AudioEvidence.fromCompilation(claim.revisionId(), audio)) {
        if (evidence.indexOrdinal() == null) {
          continue;
        }
        long start = evidence.startMs() * 16, end = evidence.endMs() * 16;
        String pcmSha = ModelValues.sha256(Arrays.copyOfRange(pcm, (int) start * 2, (int) end * 2));
        String physical = RetrievalProjection.physicalSegmentId(generation, evidence.id());
        var entry =
            new RetrievalProjection.Entry(
                physical, WORKSPACE, document, generation, pcmSha, List.of(1.0, 0.0));
        String digest = RetrievalProjection.entryDigest(entry);
        items.add(entry);
        digests.put(physical, digest);
        saved.add(
            new AudioVectorEntry(
                evidence.id(),
                RetrievalProjection.physicalSegmentId(
                    publication.projectionGenerationId(), evidence.id()),
                physical,
                evidence.ordinal(),
                start,
                end,
                pcmSha,
                digest));
      }
      assertEquals(List.of(0, 2), saved.stream().map(AudioVectorEntry::ordinal).toList());
      var manifest =
          new RetrievalProjection.RevisionManifest(WORKSPACE, document, generation, digests);
      verifySeed(settings.projection(), items, manifest);
      var receipt =
          new AudioVectorPublication(
              receiptId,
              publication,
              settings.target(),
              generation,
              audio.decoderRevision(),
              saved,
              manifest.sha256(),
              now);
      store.transaction(
          () -> {
            app.getBean(AudioVectorRepository.class).insert(receipt);
            return null;
          });
    }
    return new Seed(document, task, publication, generation);
  }

  private static void verifySeed(
      MilvusRestProjection.Settings settings,
      List<RetrievalProjection.Entry> entries,
      RetrievalProjection.RevisionManifest manifest) {
    try (var projection = new MilvusRestProjection(settings)) {
      projection.initialize();
      projection.upsert(entries);
      var verified = projection.verify(manifest);
      assertEquals(settings.identity(), verified.projectionIdentity());
      assertEquals(manifest.sha256(), verified.manifestSha256());
      assertEquals(entries.size(), verified.segmentCount());
    }
  }

  static PublicationVersion publication(ConfigurableApplicationContext app, String document) {
    return app.getBean(SqliteAuthorityStore.class)
        .transaction(
            () -> {
              var p =
                  app.getBean(IndexingRepository.class).activePublication(document).orElseThrow();
              return new PublicationVersion(
                  p.documentId(),
                  p.id(),
                  p.revisionId(),
                  p.projectionGenerationId(),
                  p.sourceSha256(),
                  p.parserRevision(),
                  p.target(),
                  p.manifestSha256(),
                  p.segmentCount());
            });
  }

  ConfigurableApplicationContext start(Remote remote) throws Exception {
    Path stub = directory.resolve("native-stub");
    Files.writeString(
        stub, "#!/bin/sh\nprintf called > '" + directory.resolve("native-called") + "'\nexit 91\n");
    assertTrue(stub.toFile().setExecutable(true));
    String decoderRevision;
    try (var decoder =
        new ProcessAudioDecoder(stub.toRealPath(), stub.toRealPath(), Duration.ofSeconds(5))) {
      decoderRevision = decoder.revision();
    }
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
    var application = new SpringApplication(RagApplication.class);
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

  private static byte[] wav() {
    byte[] pcm = new byte[96000];
    var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < 48000; i++) {
      samples.putShort(
          (short) (i >= 16000 && i < 32000 ? 0 : 12000 * Math.sin(2 * Math.PI * 440 * i / 16000)));
    }
    var wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
    wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    wav.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
    wav.putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(pcm.length)
        .put(pcm);
    return wav.array();
  }

  static String base(ConfigurableApplicationContext app) {
    return "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
  }

  static String vectorPath(String document, String route) {
    return "/v1/documents/" + document + "/" + route + "-vector";
  }

  static JsonNode request(
      HttpClient http,
      String base,
      String method,
      String path,
      byte[] content,
      String type,
      int status)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(25))
            .header("X-Workspace-Id", WORKSPACE)
            .header("X-Principal-Id", "owner")
            .header("Origin", base);
    if (type != null) {
      request.header("Content-Type", type);
    }
    var response =
        http.send(
            request
                .method(
                    method,
                    content == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  /**
   * Same strict schema/digest protocol shape as the existing native fixture, with synthetic values.
   */
  static final class Remote implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();
    private final Map<String, Map<String, JsonNode>> rows = new ConcurrentHashMap<>();
    final List<Call> requests = new CopyOnWriteArrayList<>();

    record Call(String path, JsonNode body) {}

    volatile String corruptCollection = "";
    volatile String omitCollection = "";
    volatile String duplicateCollection = "";
    volatile long delayMillis;

    Remote() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(new Call(path, body));
        if (delayMillis > 0) {
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("Synthetic delayed response interrupted");
          }
        }
        Object response;
        if (path.equals("/embeddings")) {
          if (!body.path("input").isArray()) {
            throw new IOException("This fixture does not execute image embeddings");
          }
          var data = new ArrayList<Object>();
          for (int i = 0; i < body.path("input").size(); i++) {
            data.add(Map.of("index", i, "embedding", List.of(1.0, 0.0)));
          }
          response = Map.of("data", data);
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
              if (collection.equals(corruptCollection) && fields.containsKey("text")) {
                fields.put("text", "changed stored synthetic recall identity");
              }
              selected.add(fields);
            }
            if (collection.equals(omitCollection) && !selected.isEmpty()) {
              selected.removeLast();
            }
            if (collection.equals(duplicateCollection) && !selected.isEmpty()) {
              selected.add(selected.getFirst());
            }
            data = selected;
          } else if (path.endsWith("entities/search")) {
            data = List.of();
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
