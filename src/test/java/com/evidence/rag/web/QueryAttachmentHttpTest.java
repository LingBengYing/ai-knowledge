package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.AudioTranscriptionService;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.QueryPreparationService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.web.converter.QueryAttachmentRequestMapper;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Real Spring/SQLite/text protocols; media preparation is explicitly a deterministic Adapter. */
class QueryAttachmentHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION = "上海住宿上限是多少？";
  private static final String POLICY = "上海住宿上限为650元。";
  private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
  @TempDir static Path directory;

  @Test
  void temporaryImageSupportsLibraryAnswerWithoutIngestionAndSourceSurvivesRestart()
      throws Exception {
    assertNotNull(directory);
    try (var remote = new AnswerProtocolServer();
        var http = HttpClient.newHttpClient()) {
      var app = application(remote);
      Path data = directory.resolve("data");
      String source;
      try (var context = app.run(arguments(data))) {
        assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
        assertTrue(remote.requests.isEmpty());
        var settings = context.getBean(TextAdapterSettings.class);
        var text = context.getBean(TextModels.class);
        var actor = new Actor("org-main", "owner");
        var ingestion = context.getBean(IngestionService.class);
        var indexing = context.getBean(IndexingService.class);
        var target = target(settings, text);
        byte[] content = POLICY.getBytes(StandardCharsets.UTF_8);
        var upload = ingestion.uploadDocument(actor, "library.txt", "text/plain", content);
        var parse = ingestion.claimIngestion("org-main").orElseThrow();
        assertTrue(
            ingestion.completeIngestion(
                parse, new TextParser().parse("library.txt", "text/plain", content)));
        indexing.createIndexing(actor, upload.documentId(), target);
        var claim = indexing.claimIndexing("org-main").orElseThrow();
        var entries =
            claim.items().stream()
                .map(
                    item ->
                        new RetrievalProjection.Entry(
                            RetrievalProjection.physicalSegmentId(
                                claim.projectionGenerationId(), item.evidenceId()),
                            "org-main",
                            claim.documentId(),
                            claim.projectionGenerationId(),
                            item.recallText(),
                            List.of(1.0, 0.0)))
                .toList();
        var digests = new TreeMap<String, String>();
        entries.forEach(
            entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
        var manifest =
            new RetrievalProjection.RevisionManifest(
                "org-main", claim.documentId(), claim.projectionGenerationId(), digests);
        assertTrue(
            indexing.completeIndexing(
                claim,
                digests,
                new VerifiedRevision(
                    target.projectionIdentity(), manifest.sha256(), entries.size())));
        remote.install(entries);
        String base =
            "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var state = context.getBean(MediaFixture.class);
        var body =
            JSON.writeValueAsString(
                Map.of(
                    "question",
                    QUESTION,
                    "mode",
                    "text",
                    "document_ids",
                    List.of(upload.documentId()),
                    "attachments",
                    List.of(
                        Map.of(
                            "filename",
                            "query-private.png",
                            "media_type",
                            "image/png",
                            "content_base64",
                            Base64.getEncoder().encodeToString(image())))));
        var response = request(http, base, "POST", "/v1/attachment-answers", body, true);
        assertEquals(200, response.statusCode(), response.body());
        var root = JSON.readTree(response.body());
        assertEquals("text", root.path("mode").asString());
        assertEquals("answered", root.path("result").path("status").asString(), response.body());
        assertTrue(root.path("result").path("answer").asString().contains("650"));
        assertEquals(1, root.path("query_attachments").size());
        assertFalse(response.body().contains("QUERY_PRIVATE_HINT"));
        assertFalse(response.body().contains("query-private.png"));
        assertTrue(state.calls.get() > 0);
        var citation = root.path("result").path("citations").get(0);
        assertEquals(upload.documentId(), citation.path("document_id").asString());
        source = citation.path("source_url").asString();
        assertEquals(200, request(http, base, "GET", source, null, true).statusCode());
        int calls = state.calls.get();
        int modelCalls = remote.requests.size();
        var legacySelection =
            JSON.writeValueAsString(
                Map.of(
                    "question",
                    QUESTION,
                    "mode",
                    "text",
                    "document_ids",
                    List.of("missing-document"),
                    "attachments",
                    List.of(
                        Map.of(
                            "filename",
                            "query.png",
                            "media_type",
                            "image/png",
                            "content_base64",
                            Base64.getEncoder().encodeToString(image())))));
        var shared = request(http, base, "POST", "/v1/attachment-answers", legacySelection, true);
        assertEquals(200, shared.statusCode(), shared.body());
        var sharedResult = JSON.readTree(shared.body()).path("result");
        assertEquals("answered", sharedResult.path("status").asString());
        assertEquals(
            upload.documentId(),
            sharedResult.path("citations").get(0).path("document_id").asString());
        assertTrue(state.calls.get() > calls);
        assertTrue(remote.requests.size() > modelCalls);
        calls = state.calls.get();
        modelCalls = remote.requests.size();
        var memberSource =
            HttpRequest.newBuilder(URI.create(base + source))
                .header("Authorization", "Bearer " + token("second-member", "org-main"))
                .GET()
                .build();
        var memberResponse = http.send(memberSource, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, memberResponse.statusCode(), memberResponse.body());
        assertEquals(citation, JSON.readTree(memberResponse.body()).path("citation"));
        var foreignSource =
            HttpRequest.newBuilder(URI.create(base + source))
                .header("Authorization", "Bearer " + token("owner", "other-org"))
                .header("X-Workspace-Id", "org-main")
                .header("X-Principal-Id", "owner")
                .GET()
                .build();
        assertEquals(
            401, http.send(foreignSource, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(calls, state.calls.get());
        assertEquals(modelCalls, remote.requests.size());
        assertEquals(
            401, request(http, base, "POST", "/v1/attachment-answers", body, false).statusCode());
        assertEquals(calls, state.calls.get());
        assertEquals(modelCalls, remote.requests.size());
        var crossOrigin =
            HttpRequest.newBuilder(URI.create(base + "/v1/attachment-answers"))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .header("Origin", "https://not-the-origin.invalid")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        assertEquals(
            403, http.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(
            422,
            request(http, base, "POST", "/v1/attachment-answers", body + " {}", true).statusCode());
        assertEquals(
            422,
            request(http, base, "POST", "/v1/attachment-answers?unexpected=1", body, true)
                .statusCode());
        var empty =
            JSON.writeValueAsString(
                Map.of(
                    "question",
                    QUESTION,
                    "mode",
                    "text",
                    "document_ids",
                    List.of(upload.documentId()),
                    "attachments",
                    List.of()));
        assertEquals(
            "answered",
            JSON.readTree(request(http, base, "POST", "/v1/attachment-answers", empty, true).body())
                .path("result")
                .path("status")
                .asString());
        assertEquals(calls, state.calls.get());
        assertTrue(ingestion.claimIngestion("org-main").isEmpty());
        var oversized =
            new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/v1/attachment-answers");
        oversized.addHeader("Authorization", "Bearer " + token());
        oversized.addHeader("Content-Type", "application/json");
        oversized.setContent(
            new byte
                [com.evidence.rag.web.converter.QueryAttachmentRequestMapper.MAX_REQUEST_BYTES
                    + 1]);
        var oversizedResponse = new org.springframework.mock.web.MockHttpServletResponse();
        try {
          context
              .getBean(com.evidence.rag.security.web.AuthenticationFilter.class)
              .doFilter(
                  oversized,
                  oversizedResponse,
                  (request, responseForServlet) ->
                      context
                          .getBean("fixtureAttachmentServlet", ServletRegistrationBean.class)
                          .getServlet()
                          .service(request, responseForServlet));
          assertEquals(413, oversizedResponse.getStatus());
          assertEquals(
              "query_request_too_large",
              JSON.readTree(oversizedResponse.getContentAsString()).path("error_code").asString());
        } finally {
          oversized.setContent(new byte[0]);
        }
        state.block = true;
        var slow =
            HttpRequest.newBuilder(URI.create(base + "/v1/attachment-answers"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var waiting = http.sendAsync(slow, HttpResponse.BodyHandlers.ofString());
        assertTrue(state.entered.await(3, TimeUnit.SECONDS));
        assertEquals(
            429, request(http, base, "POST", "/v1/attachment-answers", body, true).statusCode());
        var timedOut = waiting.get(8, TimeUnit.SECONDS);
        assertEquals(408, timedOut.statusCode(), timedOut.body());
        assertTrue(
            state.interrupted.await(3, TimeUnit.SECONDS),
            "HTTP timeout must cancel its service waiter");
        state.block = false;
        assertEquals(
            200, request(http, base, "POST", "/v1/attachment-answers", body, true).statusCode());
      }
      int calls = remote.requests.size();
      try (var context = application(remote).run(arguments(data))) {
        assertEquals(data, context.getBean(RagProperties.class).dataDirectory());
        String base =
            "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        assertEquals(200, request(http, base, "GET", source, null, true).statusCode());
        assertEquals(calls, remote.requests.size());
        assertEquals(0, context.getBean(MediaFixture.class).calls.get());
      }
    }
  }

  private static SpringApplication application(AnswerProtocolServer remote) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var app = new SpringApplication(RagApplication.class, FixtureConfiguration.class);
    app.setEnvironment(environment);
    var defaults = new LinkedHashMap<String, Object>(remote.environment());
    defaults.put("RAG_JWT_SECRET", SECRET);
    app.setDefaultProperties(defaults);
    return app;
  }

  private static String[] arguments(Path data) {
    return new String[] {
      "--server.port=0",
      "--server.address=127.0.0.1",
      "--rag.environment=test",
      "--rag.auth-mode=jwt",
      "--rag.workspace-id=org-main",
      "--rag.data-directory=" + data,
      "--rag.answers.enabled=true",
      "--rag.ingestion.enabled=false",
      "--rag.indexing.enabled=false",
      "--rag.visual.enabled=false",
      "--rag.audio.enabled=false",
      "--rag.video.enabled=false",
      "--rag.query-attachments.enabled=false"
    };
  }

  private static HttpResponse<String> request(
      HttpClient http, String base, String method, String path, String body, boolean authenticated)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    if (authenticated) {
      request.header("Authorization", "Bearer " + token());
    }
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static IndexTarget target(TextAdapterSettings settings, TextModels text) {
    return new IndexTarget(
        settings.projection().embeddingIdentity(),
        settings.projection().identity(),
        text.revision(),
        settings.projection().dimension());
  }

  private static String token() throws Exception {
    return token("owner", "org-main");
  }

  private static String token(String principal, String workspace) throws Exception {
    Instant now = Instant.now();
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("evidence-rag")
                .audience("evidence-rag-web")
                .subject(principal)
                .claim("workspace_id", workspace)
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(120)))
                .build());
    jwt.sign(new MACSigner(SECRET));
    return jwt.serialize();
  }

  private static byte[] image() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var stream = new MemoryCacheImageOutputStream(bytes)) {
      ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", stream);
    }
    return bytes.toByteArray();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixtureConfiguration {
    @Bean
    MediaFixture mediaFixture() {
      return new MediaFixture();
    }

    @Bean
    QueryAttachmentService fixtureQueryAttachments(
        MediaFixture media,
        TextModels text,
        RetrievalProjection projection,
        TextAdapterSettings settings) {
      return new QueryAttachmentService(
          media.preparation(), media, text, projection, target(settings, text));
    }

    @Bean
    ServletRegistrationBean<BoundedMediaQueryServlet> fixtureAttachmentServlet(
        AnswerService answers, JsonMapper json, ProblemHandler errors) {
      var registration =
          new ServletRegistrationBean<>(
              new BoundedMediaQueryServlet(
                  (actor, body) -> {
                    var command = QueryAttachmentRequestMapper.command(body);
                    if (command.mode() == QueryAnswerMode.IMAGE) {
                      throw new ApplicationException(
                          FailureKind.UNAVAILABLE, "query_attachment_unavailable", "附件提问暂不可用。");
                    }
                    return answers.answerAttached(actor, command);
                  },
                  QueryAttachmentRequestMapper.MAX_REQUEST_BYTES,
                  5000,
                  2000,
                  1,
                  json,
                  errors),
              "/v1/attachment-answers");
      registration.setAsyncSupported(true);
      return registration;
    }
  }

  private static final class MediaFixture implements VisionModels, ImageOcr, QueryRankingModels {
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean block;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);

    public String revision() {
      return "query-http-fixture-v1";
    }

    public Description describe(VisualImage image) {
      calls.incrementAndGet();
      if (block) {
        entered.countDown();
        try {
          new CountDownLatch(1).await();
        } catch (InterruptedException cancelled) {
          interrupted.countDown();
          Thread.currentThread().interrupt();
          throw new TextModels.Failure("model_interrupted");
        }
      }
      return new Description("QUERY_PRIVATE_HINT Shanghai travel policy");
    }

    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("Query image cannot prove facts");
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("Query image cannot prove facts");
    }

    public Optional<ParsedImage> read(VisualImage image) {
      return Optional.empty();
    }

    public List<TextModels.Ranked> rank(PreparedQuery query, List<QueryRankCandidate> candidates) {
      assertEquals(QUESTION, query.originalQuestion());
      assertEquals(1, query.queryImages().size());
      return IntStream.range(0, candidates.size())
          .mapToObj(index -> new TextModels.Ranked(index, 0.99))
          .toList();
    }

    QueryPreparationService preparation() {
      var audioModels =
          new AudioModels() {
            public String revision() {
              return "query-http-asr-v1";
            }

            public Transcript transcribe(byte[] bytes) {
              throw new AssertionError("Image query must not transcribe");
            }

            public void close() {}
          };
      var audioDecoder =
          new AudioDecoder() {
            public String revision() {
              return "query-http-audio-v1";
            }

            public DecodedAudio decode(String filename, String mime, byte[] bytes) {
              throw new AssertionError("Image query must not decode audio");
            }

            public void close() {}
          };
      var videoDecoder =
          new VideoDecoder() {
            public String revision() {
              return "query-http-video-v1";
            }

            public DecodedVideo decode(String filename, String mime, byte[] bytes) {
              throw new AssertionError("Image query must not decode video");
            }

            public void close() {}
          };
      var budget = Duration.ofSeconds(10);
      return new QueryPreparationService(
          this,
          this,
          new AudioCompilationService(audioDecoder, audioModels, 15, budget),
          new VideoCompilationService(
              videoDecoder, new AudioTranscriptionService(audioModels, 15, budget), this, budget),
          budget);
    }
  }
}
