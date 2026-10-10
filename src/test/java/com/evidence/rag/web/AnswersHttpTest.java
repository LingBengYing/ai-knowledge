package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.AnswersSettings;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.support.DocumentWithdrawal;
import com.evidence.rag.tool.parser.TextParser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswersHttpTest {
  @TempDir static Path directory;
  private Fixture development;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION = "上海住宿上限是多少？";
  private static final String POLICY = "上海住宿上限为650元。";

  @BeforeEach
  void start() throws Exception {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
    development =
        new Fixture(directory.resolve("development-" + UUID.randomUUID()), "development_headers");
  }

  @AfterEach
  void stop() {
    if (development != null) {
      development.close();
    }
  }

  @Test
  void answersOnlyUsesIndependentDefaultsWithoutStartingJobsOrClaimingUiAndProduction()
      throws Exception {
    var response = development.request("GET", "/v1/config", Map.of(), null);
    assertEquals(200, response.statusCode());
    var config = JSON.readTree(response.body());
    assertEquals("text_answers", config.path("migration_stage").asString());
    assertTrue(config.path("capabilities").toString().contains("\"answers\""));
    assertTrue(config.path("capabilities").toString().contains("\"sources\""));
    assertFalse(config.path("capabilities").toString().contains("text_index"));
    assertTrue(development.context.getBeansOfType(IndexingJob.class).isEmpty());
    assertTrue(development.context.getBeansOfType(IngestionJob.class).isEmpty());
    assertEquals(1, development.context.getBeansOfType(TextAdapterSettings.class).size());
    assertEquals(60000, development.context.getBean(AnswersSettings.class).timeoutMs());
    assertEquals(2, development.context.getBean(AnswersSettings.class).maxConcurrent());
    assertEquals(503, development.request("GET", "/health/ready", Map.of(), null).statusCode());
    assertEquals(
        404,
        development
            .request("POST", "/v1/documents/unknown/index", development.ownerHeaders(), null)
            .statusCode());
    String document = development.publish("runtime.txt", "owner");
    var rows =
        JSON.readTree(
            development
                .request("GET", "/v1/management/documents", development.ownerHeaders(), null)
                .body());
    for (var row : rows.path("items")) {
      if (row.path("document_id").asString().equals(document)) {
        assertFalse(row.path("can_answer").asBoolean());
      }
    }
  }

  @Test
  void malformedJsonUnknownFieldsAndInvalidDeprecatedIdsFailBeforeAnyProvider() throws Exception {
    int calls = development.remote.requests.size();
    for (String body :
        List.of(
            "{}",
            "[]",
            "null",
            "{",
            "{\"question\":\"x\",\"question\":\"y\"}",
            "{\"question\":\"x\"} {}",
            "{\"question\":3}",
            "{\"question\":\"\"}",
            "{\"question\":\"\\uD800\"}",
            "{\"question\":\"x\",\"document_ids\":null}",
            "{\"question\":\"x\",\"document_ids\":[\"../private\"]}",
            "{\"question\":\"x\",\"document_ids\":[1]}",
            "{\"question\":\"x\",\"role\":\"owner\"}")) {
      var response = development.request("POST", "/v1/answers", development.ownerHeaders(), body);
      assertEquals(
          422, response.statusCode(), "Malformed input must not enter the answer use case");
      assertEquals("invalid_request", JSON.readTree(response.body()).path("error_code").asString());
      assertSafe(response);
    }
    assertEquals(
        422,
        development
            .request(
                "POST",
                "/v1/answers?document_ids=ignored",
                development.ownerHeaders(),
                body(List.of()))
            .statusCode());
    assertEquals(
        415,
        development
            .request(
                "POST",
                "/v1/answers",
                Map.of(
                    "X-Workspace-Id",
                    "org-main",
                    "X-Principal-Id",
                    "owner",
                    "Content-Type",
                    "text/plain"),
                "question")
            .statusCode());
    assertEquals(calls, development.remote.requests.size());
  }

  @Test
  void duplicateAndLargeDeprecatedIdListsSearchTheSharedPublishedLibrary() throws Exception {
    String document = development.publish("deprecated-ids.txt", "other-owner");
    for (String request :
        List.of(
            body(List.of("doc-one", "doc-one")),
            JSON.writeValueAsString(
                Map.of(
                    "question",
                    QUESTION,
                    "document_ids",
                    java.util.stream.IntStream.range(0, 129)
                        .mapToObj(value -> "doc-" + value)
                        .toList())))) {
      var response =
          development.request("POST", "/v1/answers", development.ownerHeaders(), request);
      assertEquals(200, response.statusCode(), response.body());
      var answer = JSON.readTree(response.body());
      assertEquals("answered", answer.path("status").asString(), response.body());
      assertTrue(answer.path("reason").isNull());
      assertTrue(answer.path("answer").asString().contains("650"));
      var citation = answer.path("citations").get(0);
      assertEquals(document, citation.path("document_id").asString());
      var source =
          development.request(
              "GET", citation.path("source_url").asString(), development.ownerHeaders(), null);
      assertEquals(200, source.statusCode(), source.body());
      assertEquals(citation, JSON.readTree(source.body()).path("citation"));
    }
    assertEquals(
        2,
        development.remote.requests.stream()
            .filter(request -> request.path().equals("/embeddings"))
            .count());
  }

  @Test
  void legacyExtractiveEndpointRetainsPreparedQuestionByteBoundary() throws Exception {
    String question = "中".repeat(1366);
    assertEquals(4098, question.getBytes(StandardCharsets.UTF_8).length);
    var response =
        development.request(
            "POST",
            "/v1/answers",
            development.ownerHeaders(),
            JSON.writeValueAsString(Map.of("question", question)));
    assertEquals(200, response.statusCode(), response.body());
    var answer = JSON.readTree(response.body());
    assertEquals("abstained", answer.path("status").asString());
    assertEquals("upstream_invalid", answer.path("reason").asString());
    assertTrue(answer.path("citations").isEmpty());
    assertTrue(development.remote.requests.isEmpty());
  }

  @Test
  void explicitEmptySelectionUsesTheSharedLibraryAndReturnsAReadableSource() throws Exception {
    String document = development.publish("shared.txt", "other-owner");
    int calls = development.remote.requests.size();
    var response =
        development.request("POST", "/v1/answers", development.ownerHeaders(), body(List.of()));
    assertEquals(200, response.statusCode());
    var answer = JSON.readTree(response.body());
    assertEquals("answered", answer.path("status").asString());
    assertTrue(answer.path("reason").isNull());
    assertTrue(answer.path("answer_id").asString().matches("[a-f0-9-]{36}"));
    assertEquals(document, answer.path("citations").get(0).path("document_id").asString());
    assertTrue(development.remote.requests.size() > calls);
    assertEquals(
        200,
        development
            .request(
                "GET",
                "/v1/sources/" + answer.path("answer_id").asString() + "/1",
                development.ownerHeaders(),
                null)
            .statusCode());
    assertSafe(response);
  }

  @Test
  void realHttpAdaptersProduceAReReadableServerCitationWithoutTrustingProjectionText()
      throws Exception {
    String document = development.publish("source.txt", "owner");
    int calls = development.remote.requests.size();
    var response =
        development.request(
            "POST", "/v1/answers", development.ownerHeaders(), body(List.of(document)));
    assertEquals(200, response.statusCode(), response.body());
    var answer = JSON.readTree(response.body());
    assertEquals("answered", answer.path("status").asString(), response.body());
    assertTrue(answer.path("answer").asString().contains("650"));
    assertTrue(answer.path("reason").isNull());
    var citation = answer.path("citations").get(0);
    assertEquals(document, citation.path("document_id").asString());
    assertEquals("source.txt", citation.path("filename").asString());
    assertEquals(1, citation.path("page").asInt());
    assertEquals(1, citation.path("number").asInt());
    assertTrue(citation.path("quote").asString().contains("650"));
    String sourcePath = citation.path("source_url").asString();
    assertEquals("/v1/sources/" + answer.path("answer_id").asString() + "/1", sourcePath);
    var source = development.request("GET", sourcePath, development.ownerHeaders(), null);
    assertEquals(200, source.statusCode());
    assertEquals(citation, JSON.readTree(source.body()).path("citation"));
    assertFalse(source.body().contains("projection_generation"));
    assertFalse(source.body().contains("UNTRUSTED"));
    assertFalse(source.body().contains("page_text"));
    var stages =
        development.remote.requests.subList(calls, development.remote.requests.size()).stream()
            .map(AnswerProtocolServer.Request::path)
            .toList();
    assertTrue(stages.contains("/embeddings"));
    assertEquals(2, stages.stream().filter(path -> path.endsWith("entities/search")).count());
    assertTrue(stages.contains("/rerank"));
    assertTrue(stages.contains("/chat/completions"));
    assertTrue(
        stages.stream()
            .noneMatch(
                path ->
                    path.endsWith("/create")
                        || path.endsWith("/load")
                        || path.endsWith("/upsert")));
    assertSafe(response);
    assertSafe(source);
  }

  @Test
  void deprecatedDocumentIdsNeverNarrowTheSharedLibrary() throws Exception {
    String allowed = development.publish("allowed.txt", "owner");
    String shared = development.publish("other-owner.txt", "other-owner");
    for (var selection :
        List.of(List.of(shared), List.of(allowed, shared), List.of(allowed, "missing-document"))) {
      int calls = development.remote.requests.size();
      var response =
          development.request("POST", "/v1/answers", development.ownerHeaders(), body(selection));
      assertEquals(200, response.statusCode(), response.body());
      assertEquals("answered", JSON.readTree(response.body()).path("status").asString());
      var requests = development.remote.requests.subList(calls, development.remote.requests.size());
      assertTrue(
          requests.stream().anyMatch(request -> request.body().toString().contains(allowed)));
      assertTrue(requests.stream().anyMatch(request -> request.body().toString().contains(shared)));
    }
  }

  @Test
  void sharedSourcesIgnoreLegacyAclButRecheckOrganizationAndWithdrawal() throws Exception {
    String document = development.publish("revoked.txt", "owner");
    var response =
        development.request(
            "POST", "/v1/answers", development.ownerHeaders(), body(List.of(document)));
    assertEquals(200, response.statusCode());
    String source =
        JSON.readTree(response.body()).path("citations").get(0).path("source_url").asString();
    assertEquals(
        200,
        development
            .request(
                "GET",
                source,
                Map.of("X-Workspace-Id", "org-main", "X-Principal-Id", "stranger"),
                null)
            .statusCode());
    assertEquals(
        401,
        development
            .request(
                "GET",
                source,
                Map.of("X-Workspace-Id", "other-org", "X-Principal-Id", "owner"),
                null)
            .statusCode());
    development.revoke(document);
    assertEquals(
        200, development.request("GET", source, development.ownerHeaders(), null).statusCode());
    development.withdraw(document);
    var denied = development.request("GET", source, development.ownerHeaders(), null);
    assertEquals(404, denied.statusCode());
    assertFalse(denied.body().contains(POLICY));
  }

  @Test
  void sourceAddressAcceptsOnlyAnAnswerAndBoundedOrdinalWithoutBodyOrQuery() throws Exception {
    for (String ordinal : List.of("0", "33", "not-an-integer")) {
      assertEquals(
          422,
          development
              .request("GET", "/v1/sources/missing/" + ordinal, development.ownerHeaders(), null)
              .statusCode());
    }
    assertEquals(
        422,
        development
            .request("GET", "/v1/sources/missing/1?page=2", development.ownerHeaders(), null)
            .statusCode());
    assertEquals(
        422,
        development
            .request("GET", "/v1/sources/missing/1", development.ownerHeaders(), "{}")
            .statusCode());
    assertEquals(
        405,
        development
            .request("POST", "/v1/sources/missing/1", development.ownerHeaders(), null)
            .statusCode());
    assertEquals(
        422, development.request("POST", "/v1/answers", Map.of(), body(List.of())).statusCode());
    assertEquals(
        403,
        development
            .request(
                "POST",
                "/v1/answers",
                Map.of(
                    "X-Workspace-Id",
                    "org-main",
                    "X-Principal-Id",
                    "owner",
                    "Origin",
                    "https://untrusted.invalid"),
                body(List.of()))
            .statusCode());
  }

  @Test
  void jwtBearerAndCookieAuthorizeAnswersButHeadersNeverOverrideThem() throws Exception {
    try (var jwt = new Fixture(directory.resolve("jwt"), "jwt")) {
      String document = jwt.publish("jwt-policy.txt", "owner");
      String owner = jwt.token("owner", "org-main");
      var session =
          jwt.request(
              "POST",
              "/v1/session",
              Map.of("Origin", jwt.base),
              JSON.writeValueAsString(Map.of("token", owner)));
      assertEquals(200, session.statusCode());
      String cookie = session.headers().firstValue("set-cookie").orElseThrow();
      assertTrue(cookie.contains("HttpOnly"));
      assertTrue(cookie.contains("SameSite=Strict"));
      cookie = cookie.split(";", 2)[0];
      int calls = jwt.remote.requests.size();
      assertEquals(
          401, jwt.request("POST", "/v1/answers", Map.of(), body(List.of(document))).statusCode());
      assertEquals(
          401,
          jwt.request(
                  "POST",
                  "/v1/answers",
                  Map.of("Authorization", "Bearer " + jwt.token("owner", "other-org")),
                  body(List.of(document)))
              .statusCode());
      assertEquals(
          401,
          jwt.request(
                  "POST",
                  "/v1/answers",
                  Map.of("Authorization", "Bearer invalid-fixture", "Cookie", cookie),
                  body(List.of(document)))
              .statusCode());
      assertEquals(
          403,
          jwt.request(
                  "POST",
                  "/v1/answers",
                  Map.of("Cookie", cookie, "Origin", "https://untrusted.invalid"),
                  body(List.of(document)))
              .statusCode());
      assertEquals(calls, jwt.remote.requests.size());
      var memberHeaders =
          Map.of(
              "Authorization",
              "Bearer " + jwt.token("stranger", "org-main"),
              "X-Principal-Id",
              "owner",
              "X-Workspace-Id",
              "other-org");
      var sharedAnswer = jwt.request("POST", "/v1/answers", memberHeaders, body(List.of(document)));
      assertEquals(200, sharedAnswer.statusCode(), sharedAnswer.body());
      assertEquals("answered", JSON.readTree(sharedAnswer.body()).path("status").asString());
      var memberSession = jwt.request("GET", "/v1/session", memberHeaders, null);
      assertEquals(200, memberSession.statusCode());
      assertEquals("stranger", JSON.readTree(memberSession.body()).path("principal_id").asString());
      assertEquals("org-main", JSON.readTree(memberSession.body()).path("workspace_id").asString());
      var result =
          jwt.request(
              "POST",
              "/v1/answers",
              Map.of("Cookie", cookie, "Origin", jwt.base),
              body(List.of(document)));
      assertEquals(200, result.statusCode(), result.body());
      var answer = JSON.readTree(result.body());
      assertEquals("answered", answer.path("status").asString());
      String source = answer.path("citations").get(0).path("source_url").asString();
      assertEquals(
          200,
          jwt.request("GET", source, Map.of("Authorization", "Bearer " + owner), null)
              .statusCode());
      assertEquals(401, jwt.request("GET", source, Map.of(), null).statusCode());
      assertEquals(
          200,
          jwt.request(
                  "GET",
                  source,
                  Map.of("Authorization", "Bearer " + jwt.token("stranger", "org-main")),
                  null)
              .statusCode());
      assertEquals(
          401,
          jwt.request(
                  "GET",
                  source,
                  Map.of(
                      "Authorization",
                      "Bearer " + jwt.token("owner", "other-org"),
                      "X-Principal-Id",
                      "owner",
                      "X-Workspace-Id",
                      "org-main"),
                  null)
              .statusCode());
      assertFalse(result.body().contains(owner));
      assertFalse(result.body().contains(jwt.signingSecret));
      assertSafe(result);
    }
  }

  @Test
  void absentDocumentIdsUsesSharedPublishedDocumentsButNotForeignOrWithdrawnDocuments()
      throws Exception {
    try (var library = new Fixture(directory.resolve("all-scope"), "development_headers")) {
      String visible = library.publish("visible.txt", "owner");
      String shared = library.publish("shared.txt", "other-owner");
      String foreign = library.publish("foreign.txt", "owner", "other-org");
      String withdrawn = library.publish("withdrawn.txt", "owner");
      library.withdraw(withdrawn);
      var response =
          library.request(
              "POST",
              "/v1/answers",
              library.ownerHeaders(),
              JSON.writeValueAsString(Map.of("question", QUESTION)));
      assertEquals(200, response.statusCode(), response.body());
      var answer = JSON.readTree(response.body());
      assertEquals("answered", answer.path("status").asString());
      assertFalse(answer.path("citations").isEmpty());
      for (var citation : answer.path("citations")) {
        assertTrue(List.of(visible, shared).contains(citation.path("document_id").asString()));
      }
      assertTrue(
          library.remote.requests.stream()
              .anyMatch(request -> request.body().toString().contains(visible)));
      assertTrue(
          library.remote.requests.stream()
              .anyMatch(request -> request.body().toString().contains(shared)));
      for (var request : library.remote.requests) {
        assertFalse(request.body().toString().contains(foreign));
        assertFalse(request.body().toString().contains(withdrawn));
      }
    }
  }

  private static String body(List<String> ids) {
    return JSON.writeValueAsString(Map.of("question", QUESTION, "document_ids", ids));
  }

  private static void assertSafe(HttpResponse<String> response) {
    assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
    assertTrue(response.headers().firstValue("x-request-id").orElse("").matches("[a-f0-9-]{36}"));
    assertFalse(response.body().contains("synthetic-answer-model-credential"));
    assertFalse(response.body().contains("synthetic-answer-projection-credential"));
  }

  private static final class Fixture implements AutoCloseable {
    private final AnswerProtocolServer remote = new AnswerProtocolServer();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String signingSecret = UUID.randomUUID().toString() + UUID.randomUUID();
    private final Path dataDirectory;
    private ConfigurableApplicationContext context;
    private String base;

    Fixture(Path dataDirectory, String mode) throws Exception {
      assertNotNull(directory, "Static temporary root must exist before every HTTP startup");
      this.dataDirectory = dataDirectory;
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      var application = new SpringApplication(RagApplication.class);
      application.setEnvironment(environment);
      var defaults = new LinkedHashMap<>(remote.environment());
      defaults.put("RAG_JWT_SECRET", signingSecret);
      application.setDefaultProperties(defaults);
      try {
        context =
            application.run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--rag.environment=test",
                "--rag.workspace-id=org-main",
                "--rag.auth-mode=" + mode,
                "--rag.data-directory=" + dataDirectory,
                "--rag.jwt-issuer=answer-http",
                "--rag.jwt-audience=answer-browser",
                "--rag.ingestion.enabled=false",
                "--rag.indexing.enabled=false",
                "--rag.document-removal.enabled=true",
                "--rag.answers.enabled=true");
        assertEquals(dataDirectory, context.getBean(RagProperties.class).dataDirectory());
        assertTrue(
            remote.requests.isEmpty(), "Startup cannot make external model or projection requests");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      } catch (Exception | AssertionError failure) {
        close();
        throw failure;
      }
    }

    Map<String, String> ownerHeaders() {
      return Map.of("X-Workspace-Id", "org-main", "X-Principal-Id", "owner");
    }

    HttpResponse<String> request(
        String method, String path, Map<String, String> headers, String body) throws Exception {
      var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
      headers.forEach(request::header);
      if (body != null && !headers.containsKey("Content-Type")) {
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

    String publish(String filename, String principal) {
      return publish(filename, principal, "org-main");
    }

    String publish(String filename, String principal, String workspace) {
      var actor = new Actor(workspace, principal);
      var ingestion = context.getBean(IngestionService.class);
      var indexing = context.getBean(IndexingService.class);
      var settings = context.getBean(TextAdapterSettings.class);
      var models = context.getBean(TextModels.class);
      var target =
          new IndexTarget(
              settings.projection().embeddingIdentity(),
              settings.projection().identity(),
              models.revision(),
              2);
      byte[] content = POLICY.getBytes(StandardCharsets.UTF_8);
      var upload = ingestion.uploadDocument(actor, filename, "text/plain", content);
      var parseClaim = ingestion.claimIngestion(actor.workspaceId()).orElseThrow();
      assertTrue(
          ingestion.completeIngestion(
              parseClaim, new TextParser().parse(filename, "text/plain", content)));
      indexing.createIndexing(actor, upload.documentId(), target);
      var claim = indexing.claimIndexing(actor.workspaceId()).orElseThrow();
      var entries =
          claim.items().stream()
              .map(
                  segment ->
                      new RetrievalProjection.Entry(
                          RetrievalProjection.physicalSegmentId(
                              claim.projectionGenerationId(), segment.evidenceId()),
                          actor.workspaceId(),
                          claim.documentId(),
                          claim.projectionGenerationId(),
                          segment.recallText(),
                          List.of(1.0, 0.0)))
              .toList();
      var digests = new TreeMap<String, String>();
      entries.forEach(
          entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
      var manifest =
          new RetrievalProjection.RevisionManifest(
              actor.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
      assertTrue(
          indexing.completeIndexing(
              claim,
              digests,
              new VerifiedRevision(
                  target.projectionIdentity(), manifest.sha256(), entries.size())));
      remote.install(entries);
      return claim.documentId();
    }

    void withdraw(String document) {
      DocumentWithdrawal.withdraw(
          context.getBean(SqliteAuthorityStore.class),
          new Actor("org-main", "second-member"),
          document);
    }

    void revoke(String document) throws Exception {
      Path database = dataDirectory.resolve("java-library.db");
      assertTrue(Files.isRegularFile(database));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
          var update =
              connection.prepareStatement(
                  "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
        update.setString(1, document);
        update.setString(2, "owner");
        assertEquals(1, update.executeUpdate());
      }
    }

    String token(String principal, String workspace) throws Exception {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("answer-http")
              .audience("answer-browser")
              .subject(principal)
              .claim("workspace_id", workspace)
              .notBeforeTime(Date.from(now.minusSeconds(1)))
              .expirationTime(Date.from(now.plusSeconds(120)))
              .build();
      var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      signed.sign(new MACSigner(signingSecret));
      return signed.serialize();
    }

    @Override
    public void close() {
      if (context != null) {
        context.close();
      }
      remote.close();
      http.shutdownNow();
    }
  }
}
