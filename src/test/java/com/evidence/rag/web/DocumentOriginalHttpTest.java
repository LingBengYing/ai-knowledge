package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentLifecycleService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagementService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Saved upload bytes are readable without starting parsing, indexing or model Modules. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentOriginalHttpTest {
  private static final Actor OWNER = new Actor("org-main", "original-owner");
  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private IngestionService upload;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  void start() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    context =
        application.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=development_headers",
            "--rag.data-directory=" + directory,
            "--rag.ingestion.enabled=false",
            "--rag.indexing.enabled=false",
            "--rag.answers.enabled=false",
            "--rag.document-removal.enabled=true");
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    upload =
        new IngestionService(
            context.getBean(SqliteAuthorityStore.class),
            context.getBean(IngestionRepository.class),
            context.getBean(ManagementRepository.class),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions("original-fixture-v1"),
            "java-audio-compiler-v1:" + "a".repeat(64),
            "java-video-compiler-v1:" + "b".repeat(64));
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    client.close();
  }

  static Stream<Arguments> originals() throws Exception {
    var image = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", image));
    return Stream.of(
        Arguments.of(
            "original.pdf",
            "document",
            "application/pdf",
            Files.readAllBytes(Path.of("src/test/resources/corpus/Atlas路由器运维手册.pdf"))),
        Arguments.of(
            "original.txt", "document", "text/plain", "合成资料😀".getBytes(StandardCharsets.UTF_8)),
        Arguments.of(
            "original.md", "document", "text/markdown", "# 合成资料".getBytes(StandardCharsets.UTF_8)),
        Arguments.of("original.png", "image", "image/png", image.toByteArray()),
        Arguments.of(
            "original.wav",
            "audio",
            "audio/wav",
            Files.readAllBytes(
                Path.of("src/test/resources/multimodal-provider/synthetic-audio.wav"))),
        Arguments.of(
            "original.mp4",
            "video",
            "video/mp4",
            Files.readAllBytes(
                Path.of("src/test/resources/multimodal-provider/synthetic-video.mp4"))));
  }

  @ParameterizedTest
  @MethodSource("originals")
  void eachSavedOriginalHasExactMetadataAndPinnedBytesBeforeParsing(
      String filename, String type, String mediaType, byte[] bytes) throws Exception {
    var task = upload.uploadDocument(OWNER, filename, mediaType, bytes);
    assertEquals("queued", task.state());
    String path = originalPath(task);
    var metadata = get(path, OWNER.principalId());
    assertEquals(200, metadata.statusCode(), text(metadata));
    var body = body(metadata);
    assertEquals(8, body.size());
    assertEquals(task.documentId(), body.path("document_id").asString());
    assertEquals(task.revisionId(), body.path("revision_id").asString());
    assertEquals(filename, body.path("filename").asString());
    assertEquals(type, body.path("document_type").asString());
    assertEquals(mediaType, body.path("media_type").asString());
    assertEquals(ModelValues.sha256(bytes), body.path("source_sha256").asString());
    assertEquals(bytes.length, body.path("size_bytes").asInt());
    assertEquals(contentPath(task), body.path("content_url").asString());
    assertFalse(text(metadata).contains("original_blob"));
    assertFalse(text(metadata).contains("parser_revision"));
    var content = get(body.path("content_url").asString(), OWNER.principalId());
    assertEquals(200, content.statusCode(), text(content));
    assertEquals(mediaType, content.headers().firstValue("content-type").orElseThrow());
    assertEquals(
        bytes.length, Long.parseLong(content.headers().firstValue("content-length").orElseThrow()));
    assertEquals("private, no-store", content.headers().firstValue("cache-control").orElseThrow());
    assertEquals("nosniff", content.headers().firstValue("x-content-type-options").orElseThrow());
    assertArrayEquals(bytes, content.body());
  }

  @Test
  void readCapabilityDoesNotActivateUnrelatedModules() throws Exception {
    var config = body(get("/v1/config", null));
    assertTrue(config.path("capabilities").toString().contains("\"document_originals\""));
    assertFalse(config.path("capabilities").toString().contains("\"answers\""));
    assertEquals("management_slice", config.path("migration_stage").asString());
    assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
    assertTrue(context.getBeansOfType(RetrievalProjection.class).isEmpty());
    assertTrue(context.getBeansOfType(IngestionJob.class).isEmpty());
    assertTrue(context.getBeansOfType(IndexingJob.class).isEmpty());
    assertEquals(503, get("/health/ready", null).statusCode());
  }

  @Test
  void currentAclMissingOriginalAndPinnedRevisionAreRequiredForBothReads() throws Exception {
    var task = uploadText("authority.txt");
    var store = context.getBean(SqliteAuthorityStore.class);
    store.transaction(
        () -> {
          context
              .getBean(ManagementRepository.class)
              .insertGrant(task.documentId(), "original-reader", "reader");
          return null;
        });
    assertEquals(200, get(originalPath(task), "original-reader").statusCode());
    assertEquals(200, get(contentPath(task), "original-reader").statusCode());
    assertProblem(get(originalPath(task), "stranger"), 404);
    assertProblem(get(contentPath(task), "stranger"), 404);
    assertProblem(get(originalPath(task), null), 422);
    assertProblem(get("/v1/documents/missing/original", OWNER.principalId()), 404);
    assertProblem(
        get(contentPath(task).replace(task.revisionId(), "stale-revision"), OWNER.principalId()),
        404);
    context
        .getBean(ManagementService.class)
        .registerSyntheticDocument(
            OWNER,
            new SyntheticDocument(
                "original-metadata-only",
                "fixture.pdf",
                "document",
                "application/pdf",
                "registered",
                "a".repeat(64),
                50),
            Map.of());
    assertProblem(get("/v1/documents/original-metadata-only/original", OWNER.principalId()), 404);
    assertProblem(
        get(
            "/v1/documents/original-metadata-only/revisions/registered/content",
            OWNER.principalId()),
        404);
    execute(
        "DELETE FROM document_acl WHERE document_id=? AND principal_id='original-reader'",
        task.documentId());
    assertProblem(get(originalPath(task), "original-reader"), 404);
    assertProblem(get(contentPath(task), "original-reader"), 404);
    context.getBean(DocumentLifecycleService.class).removeDocument(OWNER, task.documentId());
    assertProblem(get(originalPath(task), OWNER.principalId()), 404);
    assertProblem(get(contentPath(task), OWNER.principalId()), 404);
  }

  @Test
  void corruptedStoredBytesAndSizeNeverProduceReadableMetadataOrContent() throws Exception {
    var corrupt = uploadText("corrupt.txt");
    execute("DROP TRIGGER corpus_source_identity");
    execute("DROP TRIGGER cleanup_corpus_documents_purge");
    execute(
        "UPDATE corpus_documents SET original_blob=? WHERE document_id=?",
        "changed".getBytes(StandardCharsets.UTF_8),
        corrupt.documentId());
    assertProblem(get(originalPath(corrupt), OWNER.principalId()), 404);
    assertProblem(get(contentPath(corrupt), OWNER.principalId()), 404);
    var wrongSize = uploadText("size.txt");
    execute("DROP TRIGGER immutable_identity");
    execute("UPDATE documents SET size_bytes=size_bytes+1 WHERE id=?", wrongSize.documentId());
    assertProblem(get(originalPath(wrongSize), OWNER.principalId()), 404);
    assertProblem(get(contentPath(wrongSize), OWNER.principalId()), 404);
  }

  @Test
  void originalReadsRejectUnexpectedQueryAndRequestBody() throws Exception {
    var task = uploadText("input.txt");
    for (String path : new String[] {originalPath(task), contentPath(task)}) {
      assertProblem(get(path + "?ignored=1", OWNER.principalId()), 422);
      assertProblem(request(path, OWNER.principalId(), "{}".getBytes(StandardCharsets.UTF_8)), 422);
    }
  }

  private TaskResult uploadText(String filename) {
    return upload.uploadDocument(
        OWNER, filename, "text/plain", "fixture".getBytes(StandardCharsets.UTF_8));
  }

  private static String originalPath(TaskResult task) {
    return "/v1/documents/" + task.documentId() + "/original";
  }

  private static String contentPath(TaskResult task) {
    return "/v1/documents/" + task.documentId() + "/revisions/" + task.revisionId() + "/content";
  }

  private void execute(String sql, Object... arguments) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < arguments.length; index++) {
        statement.setObject(index + 1, arguments[index]);
      }
      statement.executeUpdate();
    }
  }

  private HttpResponse<byte[]> get(String path, String principal) throws Exception {
    return request(path, principal, null);
  }

  private HttpResponse<byte[]> request(String path, String principal, byte[] bytes)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    if (principal != null) {
      builder.header("X-Workspace-Id", OWNER.workspaceId()).header("X-Principal-Id", principal);
    }
    return client.send(
        builder
            .method(
                "GET",
                bytes == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static String text(HttpResponse<byte[]> response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  private JsonNode body(HttpResponse<byte[]> response) {
    return json.readTree(response.body());
  }

  private void assertProblem(HttpResponse<byte[]> response, int status) {
    assertEquals(status, response.statusCode(), text(response));
    assertTrue(
        response
            .headers()
            .firstValue("content-type")
            .orElseThrow()
            .contains("application/problem+json"));
  }
}
