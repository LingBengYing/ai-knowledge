package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Configuration;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.MilvusRestProjection.Settings;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.answer.TextGrounding;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.LiveIndexWorker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit {@code -Dtest=TextAnswersLiveIT test}: one frozen PDF and one answer, at most four paid
 * requests. Real parser/indexer JVMs and production model/vector Adapters are used without retries.
 * This bounded sample is not general quality, production proxy support, or production acceptance.
 */
class TextAnswersLiveIT {
  private static final String PREFIX = "RAG_TEXT_ANSWERS_IT_";
  private static final URI PROVIDER = URI.create("https://api.siliconflow.cn/v1");
  private static final String FILENAME = "星河制造差旅政策.pdf";
  private static final String PDF_SHA =
      "7baf4e2206a9779b59a6252217c86e610897d8e6980e06b1f05a512732f46752";
  private static final String QUESTION = "上海住宿标准是多少？";
  private static final String EXPECTED_QUOTE = "上海住宿标准为每晚650元";
  private static final Actor OWNER = new Actor("org-main", "live-owner");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration INDEX_TIMEOUT = Duration.ofMinutes(3);
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  @TempDir Path directory;

  @Test
  void administrativeResponseLimitCancelsBeforeBufferingOverflow() {
    var accepted = administrativeBody();
    var acceptedCancelled = new AtomicBoolean();
    accepted.onSubscribe(subscription(acceptedCancelled));
    accepted.onNext(List.of(ByteBuffer.allocate(32768)));
    accepted.onNext(List.of(ByteBuffer.allocate(32768)));
    accepted.onComplete();
    assertEquals(65536, accepted.getBody().toCompletableFuture().join().length);
    assertFalse(acceptedCancelled.get());

    var oversized = administrativeBody();
    var cancelled = new AtomicBoolean();
    oversized.onSubscribe(subscription(cancelled));
    oversized.onNext(List.of(ByteBuffer.allocate(32768)));
    oversized.onNext(List.of(ByteBuffer.allocate(32768), ByteBuffer.allocate(1)));
    assertTrue(cancelled.get(), "Cancel on overflow without waiting for onComplete");
    assertTrue(oversized.getBody().toCompletableFuture().isCompletedExceptionally());
    oversized.onComplete();
    assertTrue(oversized.getBody().toCompletableFuture().isCompletedExceptionally());
  }

  private static Flow.Subscription subscription(AtomicBoolean cancelled) {
    return new Flow.Subscription() {
      @Override
      public void request(long count) {}

      @Override
      public void cancel() {
        cancelled.set(true);
      }
    };
  }

  private static HttpResponse.BodySubscriber<byte[]> administrativeBody() {
    return new HttpResponse.BodySubscriber<>() {
      private final HttpResponse.BodySubscriber<byte[]> delegate =
          HttpResponse.BodySubscribers.ofByteArray();
      private Flow.Subscription upstream;
      private int received;
      private boolean terminated;

      @Override
      public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        if (upstream != null) {
          subscription.cancel();
          return;
        }
        upstream = subscription;
        delegate.onSubscribe(subscription);
      }

      @Override
      public void onNext(List<ByteBuffer> buffers) {
        if (terminated) {
          return;
        }
        for (ByteBuffer buffer : buffers) {
          if (buffer.remaining() > 65536 - received) {
            terminated = true;
            upstream.cancel();
            delegate.onError(new IOException("Milvus administrative response exceeds its budget"));
            return;
          }
          received += buffer.remaining();
        }
        delegate.onNext(buffers);
      }

      @Override
      public void onError(Throwable failure) {
        if (!terminated) {
          terminated = true;
          delegate.onError(failure);
        }
      }

      @Override
      public void onComplete() {
        if (!terminated) {
          terminated = true;
          delegate.onComplete();
        }
      }
    };
  }

  @Test
  void fixedPdfIsPublishedByRealWorkersAndAnswersWithCurrentAuthorizedSources() throws Exception {
    LiveSettings settings = LiveSettings.fromEnvironment();
    assertNotNull(directory, "Authority must use a fresh JUnit temporary directory");
    assertEquals(21, Runtime.version().feature(), "This live evidence requires an actual JDK 21");
    assertTrue(Files.isExecutable(Path.of(System.getProperty("java.home"), "bin", "java")));
    assertFalse(
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
            .isBlank(),
        "Real workers require the current test classes and dependency classpath");
    byte[] pdf;
    try (var input = getClass().getResourceAsStream("/corpus/" + FILENAME)) {
      assertNotNull(input, "The original golden PDF must be on the test classpath");
      pdf = input.readAllBytes();
    }
    assertEquals(PDF_SHA, ModelValues.sha256(pdf), "Do not replace the frozen golden input");

    try (var authority = new AuthorityTestContext(directory)) {
      var uploaded = authority.ingestion().uploadDocument(OWNER, FILENAME, "application/pdf", pdf);
      var parser =
          new IngestionTaskProcessor(authority.ingestion(), OWNER.workspaceId(), REQUEST_TIMEOUT);
      var parseClaim = parser.claim().orElseThrow();
      assertEquals(uploaded.taskId(), parseClaim.jobId());
      assertEquals(1, parseClaim.attempt());
      parser.process(parseClaim);
      var parsedTask = authority.ingestion().ingestionStatus(OWNER, uploaded.taskId());
      assertEquals("parsed", parsedTask.state(), "The real parser must complete successfully");
      assertNull(parsedTask.errorCode());
      ParsedText parsed = authority.ingestion().parsedEvidence(OWNER, uploaded.documentId());
      int batchSize = singleEmbeddingBatchLimit(settings.models(), settings.projection());
      assertTrue(batchSize > 0, "The configured budgets must allow an embedding batch");
      assertTrue(
          !parsed.segments().isEmpty() && parsed.segments().size() <= batchSize,
          "Stop before paid calls: the entire PDF must fit in one production embedding batch");
      assertTrue(parsed.pages().stream().anyMatch(page -> page.text().contains(EXPECTED_QUOTE)));

      try (var collection = new OwnedCollection(settings.projection());
          var writer = new MilvusRestProjection(settings.projection());
          var realModels = new OpenAiCompatibleModels(settings.models())) {
        collection.initialize(writer);
        var target =
            new IndexTarget(
                settings.projection().embeddingIdentity(),
                settings.projection().identity(),
                realModels.revision(),
                settings.models().embeddingDimensions());
        var indexer =
            new IndexingTaskProcessor(
                authority.indexing(),
                OWNER.workspaceId(),
                target,
                INDEX_TIMEOUT,
                timeout ->
                    LiveIndexWorker.create(
                        settings.models(),
                        settings.projection(),
                        timeout,
                        settings.proxyHost(),
                        settings.proxyPort()));
        var queued = indexer.create(OWNER, uploaded.documentId());
        IndexClaim claim = indexer.claim().orElseThrow();
        assertEquals(queued.taskId(), claim.jobId());
        assertEquals(1, claim.attempt());
        assertEquals(parsed.segments().size(), claim.items().size());
        assertTrue(
            claim.items().size() <= batchSize, "The paid child must retain the one-batch bound");
        assertEquals(PDF_SHA, claim.sourceSha256());
        assertEquals(TextParser.REVISION, claim.parserRevision());
        indexer.process(claim);
        var indexed = authority.indexing().indexingStatus(OWNER, queued.taskId());
        assertEquals(
            "indexed",
            indexed.state(),
            "Real embedding, Milvus verification and publication must succeed");
        assertNull(indexed.errorCode());
        assertEquals(1, indexed.attempt());
        assertNotNull(indexed.indexPublicationId());
        var evidence =
            new EvidenceService(
                authority.store(),
                new EvidenceRepository(authority.store()),
                new ManagementRepository(authority.store()),
                new DocumentPermissionPolicy());
        var selection = DocumentSelection.selected(List.of(claim.documentId()));
        var scope = evidence.snapshot(OWNER, selection, target);
        assertEquals(1, scope.publications().size());
        var publication = scope.publications().getFirst();
        assertEquals(indexed.indexPublicationId(), publication.publicationId());
        assertEquals(claim.revisionId(), publication.sourceRevisionId());
        assertEquals(claim.projectionGenerationId(), publication.projectionGenerationId());
        assertNotEquals(publication.sourceRevisionId(), publication.projectionGenerationId());
        assertEquals(target, publication.target());
        assertEquals(claim.items().size(), publication.segmentCount());
        var physicalIds =
            claim.items().stream()
                .map(
                    segment ->
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), segment.evidenceId()))
                .toList();
        assertEquals(claim.items().size(), evidence.hydrate(scope, physicalIds).size());
        var countedModels = new CountedModels(realModels);
        // A new instance has never called initialize; AnswerService must use read-only preparation.
        try (var reader = new MilvusRestProjection(settings.projection());
            var answers =
                new AnswerService(
                    evidence, countedModels, reader, target, Duration.ofMinutes(4), 1)) {
          var unavailable =
              assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          OWNER,
                          new AnswerCommand(
                              QUESTION,
                              DocumentSelection.selected(
                                  List.of(claim.documentId(), "missing-live-document")))));
          assertEquals(FailureKind.NOT_FOUND, unavailable.kind());
          countedModels.assertCounts(0);
          var answer = answers.answer(OWNER, new AnswerCommand(QUESTION, selection));
          assertEquals(
              "answered", answer.status(), "A refusal or upstream failure is not live acceptance");
          assertNull(answer.reason());
          assertTrue(answer.answer().contains("650元"));
          assertFalse(answer.citations().isEmpty());
          assertTrue(answer.citations().stream().anyMatch(c -> c.quote().contains(EXPECTED_QUOTE)));
          verifySources(answers, answer, publication, parsed);
          var denied =
              assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.source(
                          new Actor(OWNER.workspaceId(), "other-live-principal"),
                          answer.answerId(),
                          answer.citations().getFirst().number()));
          assertEquals(FailureKind.NOT_FOUND, denied.kind());
          countedModels.assertCounts(1);
          verifyTrace(answer, publication, target);
        }
      }
    }
  }

  private static int singleEmbeddingBatchLimit(Configuration models, Settings projection) {
    // Mirror the existing worker's byte-bound batch formula solely to reject costs before
    // execution.
    int dimension = models.embeddingDimensions();
    int modelBatch = (models.maxResponseBytes() - 1024) / (dimension * 128 + 1024);
    int projectionBatch =
        (projection.maxResponseBytes() - 1024)
            / (RetrievalProjection.MAX_TEXT_BYTES * 6 + dimension * 32 + 2048);
    return Math.min(16, Math.min(modelBatch, projectionBatch));
  }

  private static void verifySources(
      AnswerService answers,
      AnswerResult answer,
      PublicationVersion publication,
      ParsedText parsed) {
    for (int index = 0; index < answer.citations().size(); index++) {
      var citation = answer.citations().get(index);
      assertEquals(index + 1, citation.number());
      assertEquals(publication.documentId(), citation.documentId());
      assertEquals(publication.sourceRevisionId(), citation.revisionId());
      assertEquals(PDF_SHA, citation.sourceSha256());
      assertEquals(TextParser.REVISION, citation.parserRevision());
      assertEquals(FILENAME, citation.filename());
      var page =
          parsed.pages().stream()
              .filter(p -> p.number() == citation.page())
              .findFirst()
              .orElseThrow();
      assertTrue(citation.start() >= 0 && citation.end() > citation.start());
      assertTrue(citation.end() <= page.text().codePointCount(0, page.text().length()));
      assertEquals(
          citation.quote(),
          page.text()
              .substring(
                  page.text().offsetByCodePoints(0, citation.start()),
                  page.text().offsetByCodePoints(0, citation.end())));
      assertEquals(sha(citation.quote()), citation.quoteSha256());
      assertEquals(
          "/v1/sources/" + answer.answerId() + "/" + citation.number(), citation.sourceUrl());
      var source = answers.source(OWNER, answer.answerId(), citation.number());
      assertEquals(answer.answerId(), source.answerId());
      assertEquals(citation, source.citation());
    }
  }

  private void verifyTrace(AnswerResult answer, PublicationVersion publication, IndexTarget target)
      throws SQLException {
    // Private read-only inspection of the required immutable trace; no production audit API added.
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("java-library.db"), config.toProperties());
        var header = connection.createStatement();
        var rows = header.executeQuery("SELECT * FROM query_traces")) {
      assertTrue(rows.next());
      assertEquals(answer.answerId(), rows.getString("id"));
      assertEquals(OWNER.workspaceId(), rows.getString("workspace_id"));
      assertEquals(OWNER.principalId(), rows.getString("actor_id"));
      assertEquals(0, rows.getInt("selection_all"));
      assertEquals(1, rows.getInt("scope_count"));
      assertEquals(answer.citations().size(), rows.getInt("citation_count"));
      assertEquals("answered", rows.getString("outcome"));
      assertNull(rows.getString("reason_code"));
      assertEquals(sha(QUESTION), rows.getString("question_sha256"));
      assertEquals(sha(answer.answer()), rows.getString("answer_sha256"));
      assertEquals(target.modelRevision(), rows.getString("model_revision"));
      assertEquals(AnswerService.PROMPT_REVISION, rows.getString("prompt_revision"));
      assertEquals(TextGrounding.VERSION, rows.getString("policy_revision"));
      assertFalse(rows.next(), "Invalid selection must not create an accepted-query trace");
      try (var scope = connection.createStatement();
          var documents = scope.executeQuery("SELECT * FROM query_trace_documents")) {
        assertTrue(documents.next());
        assertEquals(answer.answerId(), documents.getString("trace_id"));
        assertEquals(publication.publicationId(), documents.getString("publication_id"));
        assertFalse(documents.next());
      }
      try (var count = connection.createStatement();
          var total = count.executeQuery("SELECT COUNT(*) FROM query_trace_evidence")) {
        assertTrue(total.next());
        assertEquals(answer.citations().size(), total.getInt(1));
      }
      try (var query = connection.createStatement();
          var citations =
              query.executeQuery(
                  """
              SELECT e.*, p.entry_sha256 FROM query_trace_evidence e
              JOIN index_publication_entries p ON p.publication_id=e.publication_id
                AND p.source_segment_id=e.source_segment_id AND p.physical_segment_id=e.physical_segment_id
              ORDER BY e.citation_ordinal
              """)) {
        for (var citation : answer.citations()) {
          assertTrue(
              citations.next(), "Every trace locator must join the actual publication manifest");
          assertEquals(answer.answerId(), citations.getString("trace_id"));
          assertEquals(publication.publicationId(), citations.getString("publication_id"));
          assertEquals(citation.number(), citations.getInt("citation_ordinal"));
          assertEquals(citation.page(), citations.getInt("page_number"));
          assertEquals(citation.start(), citations.getInt("start_offset"));
          assertEquals(citation.end(), citations.getInt("end_offset"));
          assertEquals(citation.quoteSha256(), citations.getString("quote_sha256"));
          assertEquals(
              RetrievalProjection.physicalSegmentId(
                  publication.projectionGenerationId(), citations.getString("source_segment_id")),
              citations.getString("physical_segment_id"));
          assertTrue(citations.getString("entry_sha256").matches("[a-f0-9]{64}"));
          assertTrue(Double.isFinite(citations.getDouble("retrieval_score")));
          assertTrue(Double.isFinite(citations.getDouble("rerank_score")));
        }
        assertFalse(citations.next());
      }
    }
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(
        value != null
            && !value.isBlank()
            && value.length() <= 4096
            && value.equals(value.strip())
            && value.codePoints().noneMatch(c -> c < 32 || c == 127),
        "Explicit valid " + name + " is required");
    return value;
  }

  private record LiveSettings(
      Configuration models, Settings projection, String proxyHost, String proxyPort) {
    static LiveSettings fromEnvironment() {
      assertTrue(
          "true".equals(required(PREFIX + "ENABLED")),
          "Explicit live execution approval is required");
      assertTrue(
          "4".equals(required(PREFIX + "MAX_MODEL_REQUESTS")),
          "Explicit four-request budget is required");
      String host = required(PREFIX + "HTTPS_PROXY_HOST");
      String port = required(PREFIX + "HTTPS_PROXY_PORT");
      LiveIndexWorker.validateProxy(host, port);
      assertTrue(
          host.equals(System.getProperty("https.proxyHost"))
              && port.equals(System.getProperty("https.proxyPort")),
          "Parent HTTPS proxy must match the explicit child route");
      String key = required("RAG_SILICONFLOW_IT_API_KEY");
      String embeddingModel = required("RAG_SILICONFLOW_IT_EMBEDDING_MODEL");
      String rerankModel = required("RAG_SILICONFLOW_IT_RERANK_MODEL");
      String generationModel = required("RAG_SILICONFLOW_IT_GENERATION_MODEL");
      String number = required("RAG_SILICONFLOW_IT_DIMENSIONS");
      assertTrue(number.matches("[1-9][0-9]{0,3}"), "Dimensions must be an explicit integer");
      int dimension = Integer.parseInt(number);
      assertTrue(dimension >= 2 && dimension <= 8192, "Dimensions must be between 2 and 8192");
      String revision = required(PREFIX + "EMBEDDING_REVISION");
      String token = required(PREFIX + "MILVUS_TOKEN");
      URI endpoint;
      try {
        endpoint = URI.create(required(PREFIX + "MILVUS_ENDPOINT"));
      } catch (IllegalArgumentException invalid) {
        throw new AssertionError("The dedicated Milvus endpoint must be a valid URI");
      }
      assertTrue(
          "http".equals(endpoint.getScheme())
              && "127.0.0.1".equals(endpoint.getHost())
              && endpoint.getPort() >= 1024
              && endpoint.getPort() <= 65535
              && endpoint.getPort() != 19530
              && endpoint.getPort() != 9091
              && endpoint.getUserInfo() == null
              && endpoint.getQuery() == null
              && endpoint.getFragment() == null
              && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath())),
          "Only an explicit dedicated nondefault loopback Milvus port is allowed");
      String database = required(PREFIX + "MILVUS_DATABASE");
      assertTrue(
          database.matches("java_it_answers_[A-Za-z0-9_]{1,40}"),
          "Use a newly precreated java_it_answers_* database");
      // Exercise the production loader, including its secret validation and embedding identity.
      // Only this allowlist crosses the configuration boundary, never all ambient IT variables.
      var loaded =
          TextAdapterSettings.load(
              Map.ofEntries(
                  Map.entry("RAG_EMBEDDING_BASE_URL", PROVIDER.toString()),
                  Map.entry("RAG_EMBEDDING_MODEL", embeddingModel),
                  Map.entry("RAG_EMBEDDING_API_KEY", key),
                  Map.entry("RAG_EMBEDDING_DIMENSIONS", Integer.toString(dimension)),
                  Map.entry("RAG_EMBEDDING_REVISION", revision),
                  Map.entry("RAG_RERANK_BASE_URL", PROVIDER.toString()),
                  Map.entry("RAG_RERANK_MODEL", rerankModel),
                  Map.entry("RAG_RERANK_API_KEY", key),
                  Map.entry("RAG_GENERATION_BASE_URL", PROVIDER.toString()),
                  Map.entry("RAG_GENERATION_MODEL", generationModel),
                  Map.entry("RAG_GENERATION_API_KEY", key),
                  Map.entry("RAG_MILVUS_ENDPOINT", endpoint.toString()),
                  Map.entry("RAG_MILVUS_TOKEN", token),
                  Map.entry("RAG_MILVUS_DATABASE", database),
                  Map.entry(
                      "RAG_MILVUS_COLLECTION",
                      "java_it_" + UUID.randomUUID().toString().replace("-", "")),
                  Map.entry("RAG_WORKSPACE_ID", OWNER.workspaceId()),
                  Map.entry("RAG_TEXT_DEADLINE_MS", Long.toString(REQUEST_TIMEOUT.toMillis())),
                  Map.entry("RAG_TEXT_MAX_RESPONSE_BYTES", Integer.toString(MAX_RESPONSE_BYTES)),
                  Map.entry("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true")));
      return new LiveSettings(loaded.models(), loaded.projection(), host, port);
    }

    @Override
    public String toString() {
      return "LiveSettings[redacted]";
    }
  }

  private static final class CountedModels implements TextModels {
    private final TextModels delegate;
    private final AtomicInteger embeddings = new AtomicInteger();
    private final AtomicInteger rerankings = new AtomicInteger();
    private final AtomicInteger extractions = new AtomicInteger();

    CountedModels(TextModels delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      assertEquals(
          1, embeddings.incrementAndGet(), "No second query embedding request is authorized");
      return delegate.embed(texts);
    }

    @Override
    public List<Ranked> rerank(String query, List<String> texts) {
      assertEquals(1, rerankings.incrementAndGet(), "No second rerank request is authorized");
      return delegate.rerank(query, texts);
    }

    @Override
    public Extraction extract(String query, List<Evidence> evidence) {
      assertEquals(1, extractions.incrementAndGet(), "No second extraction request is authorized");
      return delegate.extract(query, evidence);
    }

    @Override
    public String revision() {
      return delegate.revision();
    }

    void assertCounts(int each) {
      assertEquals(each, embeddings.get());
      assertEquals(each, rerankings.get());
      assertEquals(each, extractions.get());
    }
  }

  private static final class OwnedCollection implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(15);
    private final Settings settings;
    private final HttpClient admin =
        HttpClient.newBuilder()
            .connectTimeout(ADMIN_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private boolean initializationAttempted;
    private boolean owned;

    OwnedCollection(Settings settings) {
      this.settings = settings;
    }

    void initialize(MilvusRestProjection writer) throws Exception {
      JsonNode collections = post("collections/list", Map.of("dbName", settings.database()));
      assertTrue(
          collections.isArray() && collections.isEmpty(),
          "The dedicated database must be empty before this run");
      assertFalse(exists(), "The random collection must not already exist");
      initializationAttempted = true;
      writer.initialize();
      owned = true;
      assertTrue(exists(), "Successful writer initialization must create the reserved collection");
    }

    private Map<String, Object> target() {
      return Map.of("dbName", settings.database(), "collectionName", settings.collection());
    }

    private boolean exists() throws Exception {
      JsonNode value = post("collections/has", target()).path("has");
      assertTrue(value.isBoolean(), "Collection presence must be explicit");
      return value.booleanValue();
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
      assertTrue(Set.of("collections/list", "collections/has", "collections/drop").contains(path));
      assertEquals(settings.database(), body.get("dbName"));
      if (!"collections/list".equals(path)) {
        assertEquals(settings.collection(), body.get("collectionName"));
      }
      var request =
          HttpRequest.newBuilder(settings.endpoint().resolve("/v2/vectordb/" + path))
              .timeout(ADMIN_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Request-Timeout", "15")
              .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      if (!settings.token().isEmpty()) {
        request.header("Authorization", "Bearer " + settings.token());
      }
      var pending = admin.sendAsync(request.build(), ignored -> administrativeBody());
      try {
        var response = pending.get(ADMIN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        assertEquals(200, response.statusCode(), "Milvus live administrative HTTP status");
        assertTrue(
            response.body().length <= 65536, "Milvus administrative response exceeds its budget");
        JsonNode parsed = JSON.readTree(response.body());
        assertNotNull(parsed);
        assertTrue(parsed.isObject() && parsed.path("code").isIntegralNumber());
        assertTrue(
            "0".equals(parsed.path("code").toString()), "Milvus administrative request failed");
        assertTrue(parsed.has("data"));
        return parsed.path("data");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Milvus live administrative request was interrupted");
      } catch (ExecutionException | TimeoutException failure) {
        throw new AssertionError("Milvus live administrative request did not complete");
      } catch (RuntimeException invalidResponse) {
        // JSON diagnostics may contain raw upstream text; do not attach the original exception.
        throw new AssertionError("Milvus live administrative response was invalid");
      } finally {
        if (!pending.isDone()) {
          pending.cancel(true);
        }
      }
    }

    @Override
    public void close() throws Exception {
      try {
        if (owned) {
          assertTrue(settings.collection().matches("java_it_[a-f0-9]{32}"));
          post("collections/drop", target());
          assertFalse(exists(), "The owned collection must be removed");
          owned = false;
        } else if (initializationAttempted) {
          throw new AssertionError(
              "Writer initialization was incomplete; reserved collection was not deleted: "
                  + settings.collection());
        }
      } finally {
        admin.shutdownNow();
      }
    }
  }
}
