package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.service.ManagementService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRemovalHttpTest {
  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  void start() {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
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
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    context
        .getBean(ManagementService.class)
        .registerSyntheticDocument(
            new Actor("org-main", "owner"),
            new SyntheticDocument(
                "removal-fixture",
                "Removal fixture.pdf",
                "document",
                "application/pdf",
                "revision-1",
                "a".repeat(64),
                50),
            Map.of("reader", "reader", "editor", "editor"));
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    client.close();
  }

  @Test
  void explicitRemovalAcceptsWithdrawalWithoutClaimingCleanupAndRepeatsItsOriginalReceipt()
      throws Exception {
    var first = remove();
    assertEquals(202, first.statusCode());
    var receipt = json.readTree(first.body());
    assertEquals(4, receipt.size());
    assertEquals("removal-fixture", receipt.path("document_id").asString());
    assertEquals("deleting", receipt.path("status").asString());
    assertEquals("pending", receipt.path("cleanup_status").asString());
    assertNotNull(Instant.parse(receipt.path("requested_at").asString()));
    assertEquals("private, no-store", first.headers().firstValue("cache-control").orElseThrow());
    assertTrue(first.headers().firstValue("x-request-id").isPresent());

    var repeated = remove();
    assertEquals(202, repeated.statusCode());
    assertEquals(receipt, json.readTree(repeated.body()));
  }

  @Test
  void removalOnlyAdvertisesWithdrawalAndLeavesUnimplementedFeaturesDisabled() throws Exception {
    seed(context, "capability-fixture");
    var config = json.readTree(request(base, "GET", "/v1/config", Map.of(), null).body());
    assertTrue(config.path("capabilities").toString().contains("\"document_removal\""));
    assertTrue(config.path("unavailable").toString().contains("\"document_delete\""));
    assertTrue(config.path("unavailable").toString().contains("\"reindex\""));
    assertEquals("management_slice", config.path("migration_stage").asString());
    assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
    assertTrue(context.getBeansOfType(RetrievalProjection.class).isEmpty());
    assertTrue(context.getBeansOfType(IngestionJob.class).isEmpty());
    assertTrue(context.getBeansOfType(IndexingJob.class).isEmpty());
    assertEquals(503, request(base, "GET", "/health/ready", Map.of(), null).statusCode());
    var listing =
        json.readTree(
            request(
                    base,
                    "GET",
                    "/v1/management/documents?q=capability-fixture",
                    identity("owner"),
                    null)
                .body());
    var document = listing.path("items").get(0);
    assertFalse(document.path("can_delete").asBoolean());
    assertFalse(document.path("can_reindex").asBoolean());
    assertFalse(document.path("can_answer").asBoolean());
    for (String action : List.of("delete", "reindex")) {
      var result =
          request(
              base,
              "POST",
              "/v1/management/document-actions",
              identity("owner"),
              json.writeValueAsString(
                  Map.of("document_ids", List.of("capability-fixture"), "action", action)));
      assertProblem(result, 501, "migration_incomplete");
    }
  }

  @Test
  void developmentIdentityOriginAndInputAreCheckedBeforeWithdrawal() throws Exception {
    seed(context, "input-fixture");
    String path = "/v1/documents/input-fixture";
    assertProblem(request(base, "DELETE", path, Map.of(), null), 422, "invalid_identity");
    for (String principal : List.of("reader", "stranger")) {
      assertProblem(request(base, "DELETE", path, identity(principal), null), 404, "not_found");
    }
    assertProblem(
        request(
            base,
            "DELETE",
            path,
            Map.of("X-Workspace-Id", "other-org", "X-Principal-Id", "owner"),
            null),
        401,
        "unauthorized");
    assertProblem(
        request(
            base,
            "DELETE",
            path,
            Map.of(
                "X-Workspace-Id",
                "org-main",
                "X-Principal-Id",
                "owner",
                "Origin",
                "https://untrusted.invalid"),
            null),
        403,
        "cross_origin_denied");
    assertProblem(
        request(base, "DELETE", path + "?ignored=1", identity("owner"), null),
        422,
        "invalid_request");
    assertProblem(request(base, "DELETE", path, identity("owner"), "{}"), 422, "invalid_request");
    assertProblem(
        request(base, "DELETE", "/v1/documents/" + "a".repeat(101), identity("owner"), null),
        422,
        "invalid_request");
    assertProblem(
        request(base, "DELETE", "/v1/documents/missing-document", identity("owner"), null),
        404,
        "not_found");
    var listed =
        request(base, "GET", "/v1/management/documents?q=input-fixture", identity("owner"), null);
    assertEquals(1, json.readTree(listed.body()).path("total").asInt());
    assertEquals(202, request(base, "DELETE", path, identity("editor"), null).statusCode());
  }

  @Test
  void defaultOffDoesNotExposeTheRouteOrClaimWithdrawalCapability() throws Exception {
    Path isolated = directory.resolve("default-off");
    try (var disabled = additionalContext(isolated, "development_headers", false, null)) {
      String address = address(disabled);
      seed(disabled, "disabled-fixture");
      assertEquals(
          404,
          request(address, "DELETE", "/v1/documents/disabled-fixture", identity("owner"), null)
              .statusCode());
      var config = json.readTree(request(address, "GET", "/v1/config", Map.of(), null).body());
      assertFalse(config.path("capabilities").toString().contains("\"document_removal\""));
      assertTrue(config.path("unavailable").toString().contains("\"document_delete\""));
      assertTrue(config.path("unavailable").toString().contains("\"reindex\""));
      assertEquals(503, request(address, "GET", "/health/ready", Map.of(), null).statusCode());
    }
  }

  @Test
  void reopeningWithRemovalDisabledDoesNotRestoreAnAlreadyWithdrawnDocument() throws Exception {
    Path isolated = directory.resolve("reopen-disabled");
    try (var enabled = additionalContext(isolated, "development_headers", true, null)) {
      String address = address(enabled);
      seed(enabled, "reopened-fixture");
      assertEquals(
          202,
          request(address, "DELETE", "/v1/documents/reopened-fixture", identity("owner"), null)
              .statusCode());
      var listing = request(address, "GET", "/v1/management/documents", identity("owner"), null);
      assertEquals(200, listing.statusCode());
      assertEquals(0, json.readTree(listing.body()).path("total").asInt());
    }
    try (var disabled = additionalContext(isolated, "development_headers", false, null)) {
      String address = address(disabled);
      assertEquals(
          404,
          request(address, "DELETE", "/v1/documents/reopened-fixture", identity("owner"), null)
              .statusCode());
      var listing = request(address, "GET", "/v1/management/documents", identity("owner"), null);
      assertEquals(200, listing.statusCode());
      assertEquals(0, json.readTree(listing.body()).path("total").asInt());
      assertEquals(0, json.readTree(listing.body()).path("items").size());
      var config = json.readTree(request(address, "GET", "/v1/config", Map.of(), null).body());
      assertFalse(config.path("capabilities").toString().contains("\"document_removal\""));
    }
  }

  @Test
  void jwtBearerAndCookieKeepCurrentIdentityAuthoritativeForWithdrawal() throws Exception {
    String signingSecret = UUID.randomUUID().toString() + UUID.randomUUID();
    try (var jwt = additionalContext(directory.resolve("jwt"), "jwt", true, signingSecret)) {
      String address = address(jwt);
      seed(jwt, "jwt-fixture");
      String path = "/v1/documents/jwt-fixture";
      String ownerToken = token("owner", "org-main", signingSecret);
      var session =
          request(
              address,
              "POST",
              "/v1/session",
              Map.of("Origin", address),
              json.writeValueAsString(Map.of("token", ownerToken)));
      assertEquals(200, session.statusCode());
      String setCookie = session.headers().firstValue("set-cookie").orElseThrow();
      assertTrue(setCookie.contains("HttpOnly"));
      assertTrue(setCookie.contains("SameSite=Strict"));
      String cookie = setCookie.split(";", 2)[0];
      assertProblem(request(address, "DELETE", path, Map.of(), null), 401, "unauthorized");
      assertProblem(request(address, "DELETE", path, identity("owner"), null), 401, "unauthorized");
      assertProblem(
          request(
              address,
              "DELETE",
              path,
              Map.of("Authorization", "Bearer " + token("owner", "other-org", signingSecret)),
              null),
          401,
          "unauthorized");
      assertProblem(
          request(
              address,
              "DELETE",
              path,
              Map.of("Cookie", cookie, "Authorization", "Bearer invalid-fixture"),
              null),
          401,
          "unauthorized");
      assertProblem(
          request(
              address,
              "DELETE",
              path,
              Map.of(
                  "Authorization",
                  "Bearer " + token("reader", "org-main", signingSecret),
                  "X-Workspace-Id",
                  "org-main",
                  "X-Principal-Id",
                  "owner"),
              null),
          404,
          "not_found");
      assertProblem(
          request(
              address,
              "DELETE",
              path,
              Map.of("Cookie", cookie, "Origin", "https://untrusted.invalid"),
              null),
          403,
          "cross_origin_denied");
      var accepted =
          request(address, "DELETE", path, Map.of("Cookie", cookie, "Origin", address), null);
      assertEquals(202, accepted.statusCode());
      var repeated =
          request(address, "DELETE", path, Map.of("Authorization", "Bearer " + ownerToken), null);
      assertEquals(202, repeated.statusCode());
      assertEquals(json.readTree(accepted.body()), json.readTree(repeated.body()));
      assertFalse(accepted.body().contains(ownerToken));
      assertFalse(accepted.body().contains(signingSecret));
    }
  }

  private static ConfigurableApplicationContext additionalContext(
      Path isolated, String authMode, boolean enabled, String signingSecret) {
    assertNotNull(isolated, "Additional HTTP directory must be explicit before Spring startup");
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    var arguments =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--rag.environment=test",
                "--rag.auth-mode=" + authMode,
                "--rag.data-directory=" + isolated,
                "--rag.ingestion.enabled=false",
                "--rag.indexing.enabled=false",
                "--rag.answers.enabled=false"));
    if (enabled) {
      arguments.add("--rag.document-removal.enabled=true");
    }
    if (signingSecret != null) {
      arguments.add("--rag.jwt-secret=" + signingSecret);
      arguments.add("--rag.jwt-issuer=removal-http");
      arguments.add("--rag.jwt-audience=removal-browser");
    }
    var result = application.run(arguments.toArray(String[]::new));
    assertEquals(isolated, result.getBean(RagProperties.class).dataDirectory());
    return result;
  }

  private static String address(ConfigurableApplicationContext application) {
    return "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port");
  }

  private static void seed(ConfigurableApplicationContext application, String id) {
    application
        .getBean(ManagementService.class)
        .registerSyntheticDocument(
            new Actor("org-main", "owner"),
            new SyntheticDocument(
                id, id + ".txt", "document", "text/plain", "revision-fixture", "a".repeat(64), 50),
            Map.of("reader", "reader", "editor", "editor"));
  }

  private static Map<String, String> identity(String principal) {
    return Map.of("X-Workspace-Id", "org-main", "X-Principal-Id", principal);
  }

  private String token(String principal, String workspace, String signingSecret) throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("removal-http")
            .audience("removal-browser")
            .subject(principal)
            .claim("workspace_id", workspace)
            .notBeforeTime(Date.from(now.minusSeconds(5)))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .build();
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    signed.sign(new MACSigner(signingSecret));
    return signed.serialize();
  }

  private HttpResponse<String> request(
      String address, String method, String path, Map<String, String> headers, String content)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(address + path)).timeout(Duration.ofSeconds(5));
    headers.forEach(builder::header);
    if (content != null) {
      builder.header("Content-Type", "application/json");
    }
    return client.send(
        builder
            .method(
                method,
                content == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(content))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertProblem(HttpResponse<String> response, int status, String code) {
    assertEquals(status, response.statusCode());
    assertEquals(code, json.readTree(response.body()).path("error_code").asString());
    assertEquals("private, no-store", response.headers().firstValue("cache-control").orElseThrow());
    assertTrue(response.headers().firstValue("x-request-id").isPresent());
  }

  private HttpResponse<String> remove() throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/v1/documents/removal-fixture"))
            .timeout(Duration.ofSeconds(5))
            .header("Origin", base)
            .header("X-Workspace-Id", "org-main")
            .header("X-Principal-Id", "owner")
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
