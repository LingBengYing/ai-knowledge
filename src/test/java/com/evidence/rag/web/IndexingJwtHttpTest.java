package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.web.converter.ManagementRequestMapper;
import com.evidence.rag.web.converter.ManagementResponseMapper;
import com.evidence.rag.worker.indexing.IndexingTestServer;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexingJwtHttpTest {
  @TempDir static Path directory;
  private final String signingSecret = UUID.randomUUID().toString() + UUID.randomUUID();
  private final JsonMapper json = JsonMapper.builder().build();
  private final HttpClient client = HttpClient.newHttpClient();
  private IndexingTestServer external;
  private ConfigurableApplicationContext context;
  private String base;

  @BeforeAll
  void start() throws Exception {
    assertNotNull(directory, "JWT HTTP directory must exist before Spring startup");
    external = new IndexingTestServer();
    var defaults = new LinkedHashMap<String, Object>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      defaults.put("RAG_" + kind + "_BASE_URL", external.endpoint().toString());
      defaults.put("RAG_" + kind + "_MODEL", "fixture-model");
      defaults.put("RAG_" + kind + "_API_KEY", "synthetic-model-credential");
    }
    defaults.put("RAG_EMBEDDING_DIMENSIONS", "2");
    defaults.put("RAG_EMBEDDING_REVISION", "fixture-v1");
    defaults.put("RAG_MILVUS_ENDPOINT", external.endpoint().toString());
    defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    defaults.put("RAG_MILVUS_COLLECTION", external.settings().projection().collection());
    defaults.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    defaults.put("RAG_TEXT_DEADLINE_MS", "10000");
    defaults.put("RAG_JWT_SECRET", signingSecret);
    var application = new SpringApplication(RagApplication.class);
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    application.setEnvironment(environment);
    application.setDefaultProperties(defaults);
    context =
        application.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=jwt",
            "--rag.workspace-id=org-main",
            "--rag.jwt-issuer=indexing-http",
            "--rag.jwt-audience=indexing-browser",
            "--rag.data-directory=" + directory,
            "--rag.ingestion.enabled=false",
            "--rag.indexing.enabled=true",
            "--rag.indexing.timeout-ms=15000");
    assertEquals(directory, context.getBean(RagProperties.class).dataDirectory());
    assertTrue(signingSecret.equals(context.getBean(RagProperties.class).jwtSecret()));
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (external != null) {
      external.releaseEmbedding.countDown();
    }
    if (context != null) {
      context.close();
    }
    if (external != null) {
      external.close();
    }
  }

  @Test
  void bearerCookieAndOrganizationProtectSharedCreateCancelRetryAndPublication() throws Exception {
    var authority = context.getBean(IngestionService.class);
    var owner = new Actor("org-main", "owner");
    byte[] content = "合成JWT索引证据650元".getBytes(StandardCharsets.UTF_8);
    var uploaded = authority.uploadDocument(owner, "jwt-index.txt", "text/plain", content);
    var parseClaim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(
            parseClaim, new TextParser().parse("jwt-index.txt", "text/plain", content)));
    String path = "/v1/documents/" + uploaded.documentId() + "/index";
    String ownerToken = token("owner", "org-main");
    String strangerToken = token("stranger", "org-main");
    var session =
        request(
            "POST",
            "/v1/session",
            Map.of("Origin", base),
            json.writeValueAsString(Map.of("token", ownerToken)));
    assertEquals(200, session.statusCode());
    String cookie = session.headers().firstValue("set-cookie").orElseThrow();
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    cookie = cookie.split(";", 2)[0];
    assertEquals(401, request("POST", path, Map.of(), null).statusCode());
    assertEquals(
        401,
        request(
                "POST",
                path,
                Map.of("Authorization", "Bearer " + token("owner", "other-org")),
                null)
            .statusCode());
    assertEquals(
        401,
        request(
                "POST",
                path,
                Map.of("Cookie", cookie, "Authorization", "Bearer invalid-fixture"),
                null)
            .statusCode());
    assertEquals(
        403,
        request("POST", path, Map.of("Cookie", cookie, "Origin", "https://untrusted.invalid"), null)
            .statusCode());
    assertTrue(external.requests.isEmpty());

    external.failureMode = "block-embedding";
    var created =
        request(
            "POST",
            path,
            Map.of(
                "Authorization",
                "Bearer " + strangerToken,
                "X-Principal-Id",
                "owner",
                "X-Workspace-Id",
                "org-main",
                "Origin",
                base),
            null);
    assertEquals(202, created.statusCode());
    String task = json.readTree(created.body()).path("task_id").asString();
    assertTrue(external.embeddingStarted.await(8, TimeUnit.SECONDS));
    String taskPath = "/v1/indexings/" + task;
    assertEquals(
        200,
        request("GET", taskPath, Map.of("Authorization", "Bearer " + strangerToken), null)
            .statusCode());
    var cancelled =
        request(
            "POST",
            taskPath + "/cancel",
            Map.of("Authorization", "Bearer " + strangerToken, "Origin", base),
            null);
    assertEquals(200, cancelled.statusCode());
    assertEquals("cancelled", json.readTree(cancelled.body()).path("state").asString());
    assertEquals(
        "cancelled",
        json.readTree(request("GET", taskPath, Map.of("Cookie", cookie), null).body())
            .path("state")
            .asString());
    assertNull(first(managementPage(owner, Map.of())).get("active_revision_id"));
    external.failureMode = "";
    external.releaseEmbedding.countDown();
    var retried =
        request("POST", taskPath + "/retry", Map.of("Cookie", cookie, "Origin", base), null);
    assertEquals(200, retried.statusCode());
    assertEquals(2, json.readTree(retried.body()).path("attempt").asInt());
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    JsonNode finished;
    do {
      var status = request("GET", taskPath, Map.of("Cookie", cookie), null);
      assertEquals(200, status.statusCode());
      assertFalse(status.body().contains(ownerToken));
      assertFalse(status.body().contains(signingSecret));
      finished = json.readTree(status.body());
      if (!List.of("queued", "processing").contains(finished.path("state").asString())) {
        break;
      }
      Thread.sleep(30);
    } while (System.nanoTime() < deadline);
    assertEquals("indexed", finished.path("state").asString());
    assertEquals(2, finished.path("attempt").asInt());
    var row = first(managementPage(owner, Map.of()));
    assertNotNull(row.get("active_revision_id"));
    assertNotNull(row.get("index_publication_id"));
    assertEquals(false, row.get("can_answer"));
    assertEquals(
        409,
        request("POST", taskPath + "/retry", Map.of("Cookie", cookie, "Origin", base), null)
            .statusCode());
  }

  private String token(String principal, String workspace) throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("indexing-http")
            .audience("indexing-browser")
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
      String method, String path, Map<String, String> headers, String content) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> first(Map<String, Object> page) {
    return ((List<Map<String, Object>>) page.get("items")).getFirst();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> managementPage(Actor actor, Map<String, String> query) {
    var result =
        context
            .getBean(ManagementService.class)
            .listDocuments(actor, ManagementRequestMapper.query(query));
    return json.convertValue(ManagementResponseMapper.page(result), Map.class);
  }
}
