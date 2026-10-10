package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.support.DocumentWithdrawal;
import com.evidence.rag.support.VideoCompilationFixture;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Spring, HTTP, SQLite and provider adapters. Compiled media and remote responses are explicit
 * synthetic fixtures; this test does not certify native decoding or model quality.
 */
class ManagedMediaRoleSwitchMainlineHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final Actor MEMBER = new Actor("org-main", "member-without-acl");
  private static final String INITIAL = "fixture-model";
  private static final String GENERATION = "fixture-generation-v2";
  private static final String RERANK = "fixture-rerank-v3";
  private static final String TEXT = "星港项目的预算为47万元。";
  private static final String TEXT_QUESTION = "星港项目的预算是多少？";
  private static final String IMAGE_QUESTION = "指示灯的颜色是什么？";
  private static final String IMAGE_FACT = "指示灯的颜色是蓝色。";
  private static final byte[] VIDEO = {
    0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p',
    '4', '2'
  };
  @TempDir Path directory;

  @Test
  void roleChangesKeepAllSavedVideoKindsAndImageSourcesReadableWithoutProcessing()
      throws Exception {
    try (var text = new AnswerProtocolServer();
        var vision = new VisionServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, text.endpoint());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      Seed seed;
      List<SavedSource> saved;
      Map<String, String> protectedRows;
      int upserts;
      try (var context = start(text, vision, projection)) {
        String base = base(context);
        activate(http, base, 1);
        seed = publish(context, http, base);
        saved = answers(http, base, seed);
        assertEquals(
            List.of("video_frame", "video_frame_ocr", "video_subtitle", "image_region"),
            saved.stream().map(s -> s.citation().path("kind").asString()).toList());
        for (var source : saved) {
          assertSource(http, base, source);
          assertSource(http, base, source, MEMBER);
          json(http, base, "GET", source.url(), null, 422, null);
          json(http, base, "GET", source.url(), null, 401, new Actor("other-org", "owner"));
        }
        protectedRows = protectedRows(context, saved);
        upserts = projection.committedUpserts.size();
        int calls = calls(text, vision, projection);
        switchRoles(http, base, 1, INITIAL, GENERATION);
        assertEquals(calls, calls(text, vision, projection));

        // Actual 0033 business RED: activation succeeds, then this saved video GET returns 503.
        for (var source : saved) {
          assertSource(http, base, source);
        }
        assertEquals(calls, calls(text, vision, projection));
        assertCapabilities(http, base);
        assertVisualUsesCurrentRoles(http, base, seed, text, vision, INITIAL, GENERATION);
        assertTextUsesCurrentRoles(http, base, seed.text(), text, INITIAL, GENERATION);
        assertEquals(protectedRows, protectedRows(context, saved));
        assertEquals(upserts, projection.committedUpserts.size());

        calls = calls(text, vision, projection);
        switchRoles(http, base, 2, RERANK, GENERATION);
        for (var source : saved) {
          assertSource(http, base, source);
        }
        assertEquals(calls, calls(text, vision, projection));
        assertTextUsesCurrentRoles(http, base, seed.text(), text, RERANK, GENERATION);
        assertEquals(protectedRows, protectedRows(context, saved));
        assertEquals(upserts, projection.committedUpserts.size());
        assertFalse(Files.exists(directory.resolve("native-called")));
      }
      int calls = calls(text, vision, projection);
      try (var context = start(text, vision, projection)) {
        String base = base(context);
        assertEquals(calls, calls(text, vision, projection));
        assertEquals(
            3,
            json(http, base, "GET", "/v1/model-configuration", null, 200)
                .path("active_version")
                .asInt());
        for (var source : saved) {
          assertSource(http, base, source);
        }
        assertCapabilities(http, base);
        assertEquals(calls, calls(text, vision, projection));
        assertEquals(protectedRows, protectedRows(context, saved));
        assertEquals(upserts, projection.committedUpserts.size());
        assertVisualUsesCurrentRoles(http, base, seed, text, vision, RERANK, GENERATION);
        assertFalse(Files.exists(directory.resolve("native-called")));
        calls = calls(text, vision, projection);
        var image = saved.get(3);
        assertEquals(
            JSON.writeValueAsString(
                List.of(seed.video(), seed.image(), seed.text()).stream()
                    .sorted()
                    .map(id -> List.of(id))
                    .toList()),
            rows(
                context.getBean(SqliteAuthorityStore.class).libraryPath(),
                "SELECT p.document_id FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id WHERE q.trace_id=? ORDER BY p.document_id",
                image.metadata().path("answer_id").asString()));
        DocumentWithdrawal.withdraw(
            context.getBean(SqliteAuthorityStore.class), MEMBER, seed.video());
        for (var source : saved.subList(0, 3)) {
          json(http, base, "GET", source.url(), null, 404);
          assertEquals(404, bytes(http, base, source.url() + "/content").statusCode());
          assertEquals(404, bytes(http, base, source.url() + "/frame").statusCode());
          json(http, base, "GET", source.url(), null, 404, MEMBER);
          assertEquals(404, bytes(http, base, source.url() + "/content", MEMBER).statusCode());
          assertEquals(404, bytes(http, base, source.url() + "/frame", MEMBER).statusCode());
        }
        // The frozen full-library answer included the withdrawn video. Its old source must fail
        // revalidation even though the independently stored image original remains available.
        for (var actor : List.of(OWNER, MEMBER)) {
          json(http, base, "GET", image.url(), null, 404, actor);
          assertEquals(404, bytes(http, base, image.url() + "/content", actor).statusCode());
          var original =
              json(
                  http,
                  base,
                  "GET",
                  "/v1/documents/" + seed.image() + "/original",
                  null,
                  200,
                  actor);
          assertEquals(image.citation().path("revision_id"), original.path("revision_id"));
          assertEquals(image.citation().path("source_sha256"), original.path("source_sha256"));
          var content = bytes(http, base, original.path("content_url").asString(), actor);
          assertEquals(200, content.statusCode());
          assertArrayEquals(image.content(), content.body());
        }
        assertEquals(calls, calls(text, vision, projection));
      }
    }
  }

  @Test
  void unconfiguredMediaIsRefusedButAppliedRolesEnableVisualAndAttachmentProcessing()
      throws Exception {
    try (var text = new AnswerProtocolServer();
        var vision = new VisionServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, text.endpoint());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var context = start(text, vision, projection)) {
      String base = base(context);
      assertMediaProcessing(
          http,
          base,
          new Seed("unconfigured-video", "unconfigured-image", "unconfigured-text"),
          text,
          vision,
          projection,
          false);
      activate(http, base, 1);
      var seed = publish(context, http, base);
      var initial = answer(http, base, "/v1/visual-answers", seed.image(), IMAGE_QUESTION, null);
      assertEquals("answered", initial.path("status").asString(), initial.toString());
      switchRoles(http, base, 1, INITIAL, GENERATION);
      assertMediaProcessing(http, base, seed, text, vision, projection, true);
    }
  }

  private record Seed(String video, String image, String text) {}

  private record SavedSource(
      String url, JsonNode citation, JsonNode metadata, byte[] content, boolean frame) {}

  private Seed publish(ConfigurableApplicationContext context, HttpClient http, String base)
      throws Exception {
    var ingestion = context.getBean(IngestionService.class);
    var image = VideoCompilationFixture.image();
    String video = upload(http, base, "indicator.mp4", VIDEO);
    var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertEquals(video, claim.documentId());
    int length = TEXT.codePointCount(0, TEXT.length());
    var frame = new VideoFrame(0, 0, 200_000, image, 2, 2);
    var compiler = context.getBean(VideoCompilationService.class);
    assertEquals("indicator.mp4", claim.filename());
    assertEquals("video/mp4", claim.mimeType());
    assertEquals(compiler.revision(), claim.parserRevision());
    var compilation =
        new VideoCompilation(
            ModelValues.sha256(VIDEO),
            "synthetic-http-decoder-v1",
            compiler.revision(),
            0,
            2_000_000,
            List.of(
                new VideoFrameRecall(
                    frame,
                    new ImageRecall(IMAGE_FACT, context.getBean(VisionModels.class).revision()))),
            null,
            new VideoOcrCompilation(
                "synthetic-http-ocr-v1",
                List.of(
                    new VideoFrameOcr(
                        0,
                        image.sha256(),
                        new ImageDimensions(2, 2),
                        TEXT,
                        List.of(new VideoOcrSegment(0, 0, length, TEXT)),
                        List.of(new ImageTextRegion(0, length, 0, 0, 2, 2))))),
            new VideoSubtitleCompilation(
                0,
                1,
                1000,
                List.of(
                    new VideoSubtitleTrack(
                        1,
                        "mov_text",
                        1,
                        1000,
                        null,
                        List.of(
                            new VideoSubtitleCue(
                                0,
                                500,
                                1000,
                                TEXT,
                                ModelValues.sha256(TEXT.getBytes(StandardCharsets.UTF_8))))))));
    assertTrue(ingestion.completeVideoIngestion(claim, compilation));
    index(context, http, base, video);
    String visual = upload(http, base, "indicator.png", image.content());
    claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertEquals(visual, claim.documentId());
    assertTrue(
        ingestion.completeVisualIngestion(
            claim, new ImageRecall(IMAGE_FACT, context.getBean(VisionModels.class).revision())));
    index(context, http, base, visual);
    byte[] text = TEXT.getBytes(StandardCharsets.UTF_8);
    String document = upload(http, base, "budget.txt", text);
    claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertEquals(document, claim.documentId());
    assertTrue(
        ingestion.completeIngestion(
            claim, new TextParser().parse("budget.txt", "text/plain", text)));
    index(context, http, base, document);
    return new Seed(video, visual, document);
  }

  private static void index(
      ConfigurableApplicationContext context, HttpClient http, String base, String document)
      throws Exception {
    json(http, base, "POST", "/v1/documents/" + document + "/index", null, 202);
    var indexing = context.getBean(IndexingService.class);
    var claim = indexing.claimIndexing(OWNER.workspaceId()).orElseThrow();
    assertEquals(document, claim.documentId());
    var models = context.getBean(TextModels.class);
    var vectors = models.embed(claim.items().stream().map(item -> item.recallText()).toList());
    var entries = new ArrayList<RetrievalProjection.Entry>();
    for (int i = 0; i < claim.items().size(); i++) {
      var item = claim.items().get(i);
      entries.add(
          new RetrievalProjection.Entry(
              RetrievalProjection.physicalSegmentId(
                  claim.projectionGenerationId(), item.evidenceId()),
              OWNER.workspaceId(),
              document,
              claim.projectionGenerationId(),
              item.recallText(),
              vectors.get(i)));
    }
    var projection = context.getBean(RetrievalProjection.class);
    projection.initialize();
    projection.upsert(entries);
    var hashes = new TreeMap<String, String>();
    entries.forEach(entry -> hashes.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    assertTrue(
        indexing.completeIndexing(
            claim,
            hashes,
            projection.verify(
                new RetrievalProjection.RevisionManifest(
                    OWNER.workspaceId(), document, claim.projectionGenerationId(), hashes))));
  }

  private static List<SavedSource> answers(HttpClient http, String base, Seed seed)
      throws Exception {
    var saved = new ArrayList<SavedSource>();
    for (String mode : List.of("visual", "ocr", "subtitle")) {
      var result =
          answer(
              http,
              base,
              "/v1/video-answers",
              seed.video(),
              mode.equals("visual") ? IMAGE_QUESTION : TEXT_QUESTION,
              mode);
      var citation = result.path("citations").get(0);
      String url = citation.path("source_url").asString();
      saved.add(
          new SavedSource(
              url,
              citation,
              json(http, base, "GET", url, null, 200),
              VIDEO,
              !mode.equals("subtitle")));
    }
    var result = answer(http, base, "/v1/visual-answers", seed.image(), IMAGE_QUESTION, null);
    var citation = result.path("citations").get(0);
    String url = citation.path("source_url").asString();
    saved.add(
        new SavedSource(
            url,
            citation,
            json(http, base, "GET", url, null, 200),
            VideoCompilationFixture.image().content(),
            false));
    return List.copyOf(saved);
  }

  private static JsonNode answer(
      HttpClient http, String base, String route, String document, String question, String mode)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("question", question);
    body.put("document_ids", List.of(document));
    if (mode != null) {
      body.put("mode", mode);
    }
    var result = json(http, base, "POST", route, body, 200);
    assertEquals("answered", result.path("status").asString(), result.toString());
    assertFalse(result.path("citations").isEmpty());
    return result;
  }

  private static void assertSource(HttpClient http, String base, SavedSource source)
      throws Exception {
    assertSource(http, base, source, OWNER);
  }

  private static void assertSource(HttpClient http, String base, SavedSource source, Actor actor)
      throws Exception {
    assertEquals(source.metadata(), json(http, base, "GET", source.url(), null, 200, actor));
    assertEquals(source.citation(), source.metadata().path("citation"));
    var content = bytes(http, base, source.url() + "/content", actor);
    assertEquals(200, content.statusCode());
    assertEquals("no-store", content.headers().firstValue("Cache-Control").orElseThrow());
    assertArrayEquals(source.content(), content.body());
    assertEquals(
        source.citation().path("source_sha256").asString(), ModelValues.sha256(content.body()));
    if (source.url().startsWith("/v1/video-sources/")) {
      var frame = bytes(http, base, source.url() + "/frame", actor);
      if (source.frame()) {
        assertEquals(200, frame.statusCode());
        assertArrayEquals(VideoCompilationFixture.image().content(), frame.body());
      } else {
        assertEquals(404, frame.statusCode());
      }
      var ranged =
          http.send(
              headers(base, source.url() + "/content", actor)
                  .header("Range", "bytes=0-7")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(206, ranged.statusCode());
      assertArrayEquals(java.util.Arrays.copyOf(source.content(), 8), ranged.body());
    }
  }

  private void assertMediaProcessing(
      HttpClient http,
      String base,
      Seed seed,
      AnswerProtocolServer text,
      VisionServer vision,
      IndexingTestServer projection,
      boolean configured)
      throws Exception {
    int before = calls(text, vision, projection);
    int modelBefore = text.requests.size() + vision.requests.size();
    var visual =
        http.send(
            headers(base, "/v1/visual-answers")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        JSON.writeValueAsBytes(
                            Map.of(
                                "question",
                                IMAGE_QUESTION,
                                "document_ids",
                                List.of(seed.image())))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    int afterVisual = calls(text, vision, projection);
    int modelsAfterVisual = text.requests.size() + vision.requests.size();
    var attached =
        http.send(
            headers(base, "/v1/attachment-answers")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        JSON.writeValueAsBytes(
                            Map.of(
                                "question",
                                IMAGE_QUESTION,
                                "document_ids",
                                List.of(seed.image()),
                                "mode",
                                "image",
                                "attachments",
                                List.of(
                                    Map.of(
                                        "filename", "reference.png",
                                        "media_type", "image/png",
                                        "content_base64",
                                            Base64.getEncoder()
                                                .encodeToString(
                                                    VideoCompilationFixture.image().content())))))))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    int afterAttached = calls(text, vision, projection);
    int modelsAfterAttached = text.requests.size() + vision.requests.size();
    var visualBody = JSON.readTree(visual.body());
    var attachedBody = JSON.readTree(attached.body());
    if (configured) {
      assertAll(
          () -> assertEquals(200, visual.statusCode(), visual.body()),
          () -> assertEquals("answered", visualBody.path("status").asString()),
          () -> assertEquals(IMAGE_FACT, visualBody.path("answer").asString()),
          () ->
              assertEquals(
                  seed.image(), visualBody.path("citations").get(0).path("document_id").asString()),
          () ->
              assertEquals(
                  "image_region", visualBody.path("citations").get(0).path("kind").asString()),
          () -> assertTrue(modelsAfterVisual > modelBefore),
          () -> assertTrue(afterVisual > before),
          () -> assertEquals(200, attached.statusCode(), attached.body()),
          () -> assertEquals("image", attachedBody.path("mode").asString()),
          () -> assertEquals("abstained", attachedBody.path("result").path("status").asString()),
          () ->
              assertEquals("parser_failed", attachedBody.path("result").path("reason").asString()),
          () -> assertEquals(1, attachedBody.path("query_attachments").size()),
          () ->
              assertEquals(
                  "failed",
                  attachedBody.path("query_attachments").get(0).path("status").asString()),
          () ->
              assertEquals(
                  "parser_failed",
                  attachedBody.path("query_attachments").get(0).path("reason").asString()),
          () ->
              assertEquals(
                  modelsAfterVisual, modelsAfterAttached, "failed decoding must not call a model"),
          () ->
              assertEquals(
                  afterVisual, afterAttached, "failed decoding must not retrieve evidence"),
          () ->
              assertTrue(
                  Files.exists(directory.resolve("native-called")),
                  "the applied configuration must reach the controlled failing decoder"));
      return;
    }
    assertAll(
        () -> assertEquals(503, visual.statusCode(), "visual response: " + visual.body()),
        () -> assertEquals("text_configuration_required", visualBody.path("error_code").asString()),
        () ->
            assertEquals(
                0, modelsAfterVisual - modelBefore, "legacy visual model calls after role switch"),
        () -> assertEquals(before, afterVisual, "legacy visual model/projection calls"),
        () -> assertEquals(503, attached.statusCode(), "attachment response: " + attached.body()),
        () ->
            assertEquals("text_configuration_required", attachedBody.path("error_code").asString()),
        () ->
            assertEquals(
                0,
                modelsAfterAttached - modelsAfterVisual,
                "legacy attachment model calls after role switch"),
        () -> assertEquals(afterVisual, afterAttached, "legacy attachment model/projection calls"),
        () ->
            assertFalse(
                Files.exists(directory.resolve("native-called")),
                "legacy attachment decoder/OCR executed after role switch"));
  }

  private static void assertCapabilities(HttpClient http, String base) throws Exception {
    var values = new ArrayList<String>();
    json(http, base, "GET", "/v1/config", null, 200)
        .path("capabilities")
        .forEach(value -> values.add(value.asString()));
    assertTrue(values.contains("visual_sources"));
    assertTrue(values.contains("video_sources"));
    assertTrue(values.contains("visual_answers"));
    assertTrue(values.contains("video_answers"));
    assertTrue(values.contains("query_attachments"));
  }

  private static void assertVisualUsesCurrentRoles(
      HttpClient http,
      String base,
      Seed seed,
      AnswerProtocolServer text,
      VisionServer vision,
      String rerank,
      String generation)
      throws Exception {
    int before = text.requests.size();
    int visionBefore = vision.requests.size();
    var result = answer(http, base, "/v1/visual-answers", seed.image(), IMAGE_QUESTION, null);
    assertEquals(IMAGE_FACT, result.path("answer").asString());
    var citation = result.path("citations").get(0);
    assertEquals(seed.image(), citation.path("document_id").asString());
    assertEquals("image_region", citation.path("kind").asString());
    String url = citation.path("source_url").asString();
    assertSource(
        http,
        base,
        new SavedSource(
            url,
            citation,
            json(http, base, "GET", url, null, 200),
            VideoCompilationFixture.image().content(),
            false));
    assertTrue(vision.requests.size() > visionBefore);
    var calls = text.requests.subList(before, text.requests.size());
    assertTrue(calls.stream().anyMatch(c -> c.path().equals("/rerank")));
    for (var call : calls) {
      String expected =
          switch (call.path()) {
            case "/embeddings" -> INITIAL;
            case "/rerank" -> rerank;
            case "/chat/completions" -> generation;
            default -> throw new AssertionError("Unexpected text model route");
          };
      assertEquals(expected, call.body().path("model").asString());
    }
  }

  private static void assertTextUsesCurrentRoles(
      HttpClient http,
      String base,
      String document,
      AnswerProtocolServer text,
      String rerank,
      String generation)
      throws Exception {
    int before = text.requests.size();
    var result = answer(http, base, "/v1/answers", document, TEXT_QUESTION, null);
    assertTrue(result.path("answer").asString().contains("47"));
    var calls = text.requests.subList(before, text.requests.size());
    assertTrue(calls.stream().anyMatch(c -> c.path().equals("/chat/completions")));
    for (var call : calls) {
      String expected =
          switch (call.path()) {
            case "/embeddings" -> INITIAL;
            case "/rerank" -> rerank;
            case "/chat/completions" -> generation;
            default -> throw new AssertionError("Unexpected text model route");
          };
      assertEquals(expected, call.body().path("model").asString());
    }
  }

  private static void switchRoles(
      HttpClient http, String base, int current, String rerank, String generation)
      throws Exception {
    var saved =
        json(
            http,
            base,
            "PUT",
            "/v1/model-configuration",
            Map.of(
                "base_version",
                current,
                "embedding",
                Map.of("model", INITIAL, "dimensions", 2, "revision", "fixture-v1"),
                "rerank",
                Map.of("model", rerank),
                "generation",
                Map.of("model", generation)),
            200);
    assertEquals(current + 1, saved.path("version").asInt());
    activate(http, base, current + 1);
  }

  private static void activate(HttpClient http, String base, int version) throws Exception {
    var result =
        json(
            http,
            base,
            "POST",
            "/v1/model-configuration/activate",
            Map.of("version", version),
            200);
    assertEquals(version, result.path("active_version").asInt());
  }

  private ConfigurableApplicationContext start(
      AnswerProtocolServer text, VisionServer vision, IndexingTestServer projection)
      throws Exception {
    Path nativeStub = directory.resolve("synthetic-native");
    if (!Files.exists(nativeStub)) {
      String marker = directory.resolve("native-called").toString().replace("'", "'\\''");
      Files.writeString(nativeStub, "#!/bin/sh\nprintf called >> '" + marker + "'\nexit 2\n");
      assertTrue(nativeStub.toFile().setExecutable(true));
    }
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var defaults = new LinkedHashMap<String, Object>(text.environment());
    for (String role : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      defaults.put("RAG_" + role + "_MODEL", INITIAL);
    }
    defaults.put("RAG_MILVUS_ENDPOINT", projection.endpoint().toASCIIString());
    defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    defaults.put("RAG_MILVUS_COLLECTION", "java_index_process_fixture");
    defaults.put("server.port", "0");
    defaults.put("server.address", "127.0.0.1");
    defaults.put("rag.environment", "test");
    defaults.put("rag.workspace-id", "org-main");
    defaults.put("rag.auth-mode", "development_headers");
    defaults.put("rag.data-directory", directory.toString());
    for (String flag :
        List.of(
            "ingestion",
            "indexing",
            "answers",
            "document-removal",
            "visual",
            "audio",
            "video",
            "video.ocr",
            "video.subtitles",
            "image-ocr",
            "query-attachments",
            "model-configuration")) {
      defaults.put("rag." + flag + ".enabled", true);
    }
    defaults.put("rag.model-configuration.administrators", "owner");
    defaults.put("rag.model-configuration.provider-base-url", text.endpoint().toASCIIString());
    defaults.put("rag.model-configuration.allow-loopback-http", true);
    defaults.put("rag.model-configuration.deadline-ms", 5000);
    for (String prefix :
        List.of(
            "rag.visual",
            "rag.video.vision",
            "rag.video.asr",
            "rag.audio",
            "rag.query-attachments.ranking")) {
      defaults.put(prefix + ".base-url", vision.endpoint().toASCIIString());
      defaults.put(prefix + ".model", "synthetic-vision");
      defaults.put(prefix + ".api-key", "synthetic-vision-key");
      defaults.put(prefix + ".allow-loopback-http", true);
    }
    for (String prefix : List.of("rag.video", "rag.audio")) {
      defaults.put(prefix + ".ffmpeg-executable", nativeStub.toString());
      defaults.put(prefix + ".ffprobe-executable", nativeStub.toString());
    }
    for (String prefix : List.of("rag.image-ocr", "rag.video.ocr")) {
      defaults.put(prefix + ".executable", nativeStub.toString());
      defaults.put(prefix + ".revision", "synthetic-http-ocr-v1");
      defaults.put(prefix + ".language", "eng");
    }
    defaults.put("rag.answers.timeout-ms", 15000);
    var app = new SpringApplication(RagApplication.class);
    app.setEnvironment(environment);
    app.setDefaultProperties(defaults);
    // application.properties has higher precedence than defaultProperties. Pin every application
    // setting explicitly; the remaining RAG_* entries are only this fixture's adapter inputs.
    String[] arguments =
        defaults.entrySet().stream()
            .filter(
                entry -> entry.getKey().startsWith("rag.") || entry.getKey().startsWith("server."))
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
    var context = app.run(arguments);
    assertEquals("development_headers", context.getBean(RagProperties.class).authMode());
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    // Seed complete typed compilation through real Services, without racing native background work.
    context.getBean(IngestionJob.class).close();
    context.getBean(IndexingJob.class).close();
    return context;
  }

  private static Map<String, String> protectedRows(
      ConfigurableApplicationContext context, List<SavedSource> sources) throws Exception {
    Path path = context.getBean(SqliteAuthorityStore.class).libraryPath();
    var result = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "indexing_jobs",
            "indexing_attempts",
            "index_publications",
            "index_publication_entries",
            "active_corpus_publications")) {
      result.put(table, rows(path, "SELECT * FROM " + table + " ORDER BY 1,2"));
      assertFalse(result.get(table).equals("[]"), table);
    }
    for (var source : sources) {
      String answerId = source.metadata().path("answer_id").asString();
      result.put(answerId, rows(path, "SELECT * FROM query_traces WHERE id=?", answerId));
      result.put(
          answerId + "/scope",
          rows(path, "SELECT * FROM query_trace_documents WHERE trace_id=? ORDER BY 2", answerId));
    }
    return Map.copyOf(result);
  }

  private static String rows(Path path, String sql, String... parameters) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setString(i + 1, parameters[i]);
      }
      try (var result = statement.executeQuery()) {
        var values = new ArrayList<List<String>>();
        while (result.next()) {
          var value = new ArrayList<String>();
          for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
            value.add(result.getString(i));
          }
          values.add(value);
        }
        return JSON.writeValueAsString(values);
      }
    }
  }

  private static int calls(
      AnswerProtocolServer text, VisionServer vision, IndexingTestServer projection) {
    return text.requests.size() + vision.requests.size() + projection.requests.size();
  }

  private static String upload(HttpClient http, String base, String name, byte[] content)
      throws Exception {
    var response =
        http.send(
            headers(base, "/v1/documents?filename=" + name)
                .header(
                    "Content-Type",
                    name.equals("indicator.mp4") ? "video/mp4" : "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(202, response.statusCode(), response.body());
    return JSON.readTree(response.body()).path("document_id").asString();
  }

  private static String base(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  private static HttpRequest.Builder headers(String base, String path) {
    return headers(base, path, OWNER);
  }

  private static HttpRequest.Builder headers(String base, String path, Actor actor) {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Origin", base);
    if (actor != null) {
      request
          .header("X-Workspace-Id", actor.workspaceId())
          .header("X-Principal-Id", actor.principalId());
    }
    return request;
  }

  private static JsonNode json(
      HttpClient http, String base, String method, String path, Object body, int status)
      throws Exception {
    return json(http, base, method, path, body, status, OWNER);
  }

  private static JsonNode json(
      HttpClient http,
      String base,
      String method,
      String path,
      Object body,
      int status,
      Actor actor)
      throws Exception {
    var request = headers(base, path, actor);
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
    var response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(status, response.statusCode(), path + ": " + response.body());
    return JSON.readTree(response.body());
  }

  private static HttpResponse<byte[]> bytes(HttpClient http, String base, String path)
      throws Exception {
    return bytes(http, base, path, OWNER);
  }

  private static HttpResponse<byte[]> bytes(HttpClient http, String base, String path, Actor actor)
      throws Exception {
    return http.send(
        headers(base, path, actor).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static final class VisionServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    VisionServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/chat/completions", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(request);
        var content = request.path("messages").get(1).path("content");
        var input = JSON.readTree(content.get(0).path("text").asString());
        Object result;
        if (input.has("claims")) {
          var support = new ArrayList<Map<String, Object>>();
          for (var claim : input.path("claims")) {
            support.add(Map.of("index", claim.path("index").asInt(), "supported", true));
          }
          result = Map.of("complete", true, "support", support);
        } else if (input.has("question")) {
          result = Map.of("refused", false, "claims", List.of(IMAGE_FACT));
        } else {
          result = Map.of("recall_text", IMAGE_FACT);
        }
        byte[] body =
            JSON.writeValueAsBytes(
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
                                JSON.writeValueAsString(result))))));
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
