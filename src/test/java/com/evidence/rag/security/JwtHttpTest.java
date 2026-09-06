package com.evidence.rag.security;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.RagApplication;
import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.shared.Actor;
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
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real embedded Tomcat and HTTP client; no provider, user credential, or existing data access. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtHttpTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final String ISSUER = "jwt-http-test";
  private static final String AUDIENCE = "jwt-http-browser";
  @TempDir static Path directory;
  private ConfigurableApplicationContext context;
  private String base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  void start() {
    assertNotNull(directory, "HTTP test directory must exist before Spring startup");
    context =
        SpringApplication.run(
            RagApplication.class,
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=jwt",
            "--rag.workspace-id=org-main",
            "--rag.jwt-secret=" + SECRET,
            "--rag.jwt-issuer=" + ISSUER,
            "--rag.jwt-audience=" + AUDIENCE,
            "--rag.data-directory=" + directory);
    assertEquals(
        directory, context.getBean(com.evidence.rag.config.RagProperties.class).dataDirectory());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    context
        .getBean(ManagementModule.class)
        .registerSyntheticDocument(
            new Actor("org-main", "owner"),
            new ManagementModule.SyntheticDocument(
                "jwt-fixture",
                "Jwt-visible.pdf",
                "document",
                "application/pdf",
                "jwt-revision",
                "b".repeat(64),
                100),
            Map.of("reader", "reader"));
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
  }

  private String token(String principal, String issuer, String workspace) throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(AUDIENCE)
            .subject(principal)
            .claim("workspace_id", workspace)
            .notBeforeTime(Date.from(now.minusSeconds(5)))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .build();
    SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    signed.sign(new MACSigner(SECRET));
    return signed.serialize();
  }

  private HttpResponse<String> request(
      String method, String path, Map<String, String> headers, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    return client.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String sessionBody(String token) {
    return json.writeValueAsString(Map.of("token", token));
  }

  private JsonNode body(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private String sessionCookie(String token) throws Exception {
    var session = request("POST", "/v1/session", Map.of("Origin", base), sessionBody(token));
    assertEquals(200, session.statusCode());
    return session.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
  }

  private void unauthorized(HttpResponse<String> response, String token) {
    assertEquals(401, response.statusCode());
    assertEquals("Bearer", response.headers().firstValue("www-authenticate").orElseThrow());
    assertEquals("private, no-store", response.headers().firstValue("cache-control").orElseThrow());
    assertTrue(
        response
            .headers()
            .firstValue("content-type")
            .orElseThrow()
            .contains("application/problem+json"));
    assertTrue(response.headers().firstValue("x-request-id").isPresent());
    assertEquals(401, body(response).path("status").asInt());
    assertFalse(response.body().contains(token));
    assertFalse(response.body().contains(SECRET));
    assertTrue(response.headers().firstValue("set-cookie").isEmpty());
  }

  @Test
  void sameOriginExchangeSetsHttpOnlyStrictCookieAndCookieAuthorizesManagement() throws Exception {
    String token = token("owner", ISSUER, "org-main");
    var response = request("POST", "/v1/session", Map.of("Origin", base), sessionBody(token));
    assertEquals(200, response.statusCode());
    String cookie = response.headers().firstValue("set-cookie").orElseThrow();
    assertTrue(cookie.startsWith("rag_session=" + token + ";"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    assertTrue(cookie.contains("Path=/"));
    assertFalse(
        cookie.contains("Secure"), "Only this test's direct HTTP loopback session omits Secure");
    assertEquals("private, no-store", response.headers().firstValue("cache-control").orElseThrow());
    assertEquals("authenticated", body(response).path("status").asString());
    assertEquals("owner", body(response).path("principal_id").asString());
    assertEquals("org-main", body(response).path("workspace_id").asString());
    assertFalse(response.body().contains(token));
    var documents =
        request("GET", "/v1/management/documents", Map.of("Cookie", cookie.split(";", 2)[0]), null);
    assertEquals(200, documents.statusCode());
    assertEquals(1, body(documents).path("total").asInt());
    assertEquals(
        "jwt-fixture", body(documents).path("items").get(0).path("document_id").asString());
  }

  @Test
  void validStrangerSessionDoesNotInheritOwnerOrDevelopmentHeaderAccess() throws Exception {
    String cookie = sessionCookie(token("stranger", ISSUER, "org-main"));
    var documents =
        request(
            "GET",
            "/v1/management/documents",
            Map.of("Cookie", cookie, "X-Workspace-Id", "org-main", "X-Principal-Id", "owner"),
            null);
    assertEquals(200, documents.statusCode());
    assertEquals(0, body(documents).path("total").asInt());
    assertEquals(0, body(documents).path("items").size());
    assertFalse(documents.body().contains("Jwt-visible.pdf"));
  }

  @Test
  void invalidBearerCannotFallBackToValidCookieAndResponsesDoNotEchoCredentials() throws Exception {
    String token = token("owner", ISSUER, "org-main");
    String cookie = sessionCookie(token);
    var response =
        request(
            "GET",
            "/v1/management/documents",
            Map.of("Cookie", cookie, "Authorization", "Bearer invalid-test-token"),
            null);
    unauthorized(response, token);
    assertFalse(response.body().contains("invalid-test-token"));
    unauthorized(
        request(
            "GET",
            "/v1/management/documents",
            Map.of("Cookie", cookie, "Authorization", "Basic invalid-test-token"),
            null),
        token);
  }

  @Test
  void wrongIssuerAndOrganizationFailForBothBearerAndSessionExchange() throws Exception {
    for (String token :
        new String[] {
          token("owner", "wrong-issuer", "org-main"), token("owner", ISSUER, "other-organization")
        }) {
      unauthorized(
          request(
              "GET", "/v1/management/documents", Map.of("Authorization", "Bearer " + token), null),
          token);
      unauthorized(
          request("POST", "/v1/session", Map.of("Origin", base), sessionBody(token)), token);
    }
  }

  @Test
  void crossOriginSessionCreationAndSignOutAreRejectedBeforeChangingCookies() throws Exception {
    String token = token("owner", ISSUER, "org-main");
    String cookie = sessionCookie(token);
    for (String origin : new String[] {"https://evil.example", "null", "http://127.0.0.1:1"}) {
      for (String method : new String[] {"POST", "DELETE"}) {
        var response =
            request(
                method,
                "/v1/session",
                Map.of("Origin", origin, "Cookie", cookie),
                method.equals("POST") ? sessionBody(token) : null);
        assertEquals(403, response.statusCode());
        assertEquals(
            "private, no-store", response.headers().firstValue("cache-control").orElseThrow());
        assertTrue(response.headers().firstValue("set-cookie").isEmpty());
        assertFalse(response.body().contains(token));
      }
    }
  }

  @Test
  void sameOriginSignOutExpiresHttpOnlyStrictCookie() throws Exception {
    String cookie = sessionCookie(token("owner", ISSUER, "org-main"));
    var response = request("DELETE", "/v1/session", Map.of("Origin", base, "Cookie", cookie), null);
    assertEquals(200, response.statusCode());
    assertEquals("signed_out", body(response).path("status").asString());
    String deleted = response.headers().firstValue("set-cookie").orElseThrow();
    assertTrue(deleted.startsWith("rag_session=;"));
    assertTrue(deleted.contains("Max-Age=0"));
    assertTrue(deleted.contains("HttpOnly"));
    assertTrue(deleted.contains("SameSite=Strict"));
    assertTrue(deleted.contains("Path=/"));
    assertEquals("private, no-store", response.headers().firstValue("cache-control").orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "[]",
        "{\"token\":null}",
        "{\"token\":123}",
        "{\"token\":\"\"}",
        "{\"token\":\"x\",\"principal_id\":\"owner\"}",
        "{\"token\":\"x\",\"token\":\"y\"}"
      })
  void invalidSessionJsonIsRejectedWithoutCookie(String invalid) throws Exception {
    var response = request("POST", "/v1/session", Map.of("Origin", base), invalid);
    assertEquals(422, response.statusCode());
    assertTrue(
        response
            .headers()
            .firstValue("content-type")
            .orElseThrow()
            .contains("application/problem+json"));
    assertTrue(response.headers().firstValue("set-cookie").isEmpty());
    assertFalse(response.body().contains(SECRET));
  }
}
