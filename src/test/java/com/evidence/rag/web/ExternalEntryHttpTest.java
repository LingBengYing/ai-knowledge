package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.security.web.ExternalEntryPolicy;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalEntryHttpTest {
  private static final String ORIGIN = "https://knowledge.example.invalid";
  @TempDir static Path directory;
  private final String secret = UUID.randomUUID().toString() + UUID.randomUUID();
  private final HttpClient client = HttpClient.newHttpClient();
  private ConfigurableApplicationContext context;
  private String base;

  @BeforeAll
  void start() {
    context =
        SpringApplication.run(
            RagApplication.class,
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--rag.environment=test",
            "--rag.auth-mode=jwt",
            "--rag.jwt-secret=" + secret,
            "--rag.jwt-issuer=external-entry-fixture",
            "--rag.jwt-audience=fixture-browser",
            "--rag.data-directory=" + directory,
            "--rag.external-entry.enabled=true",
            "--rag.external-entry.public-origin=" + ORIGIN);
    assertTrue(context.getBean(ExternalEntryPolicy.class).enabled());
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
  }

  private String token(String workspace, long seconds) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("external-entry-fixture")
                .audience("fixture-browser")
                .claim("workspace_id", workspace)
                .subject("synthetic-owner")
                .expirationTime(Date.from(Instant.now().plusSeconds(seconds)))
                .build());
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private HttpResponse<String> exchange(String token, String origin) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + "/v1/session"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (origin != null) {
      request.header("Origin", origin);
    }
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void originalExternalOriginCreatesSecureSessionAndActualJwtIsRequiredForReadback()
      throws Exception {
    HttpResponse<String> login = exchange(token("org-main", 300), ORIGIN);
    assertEquals(200, login.statusCode());
    String cookie = login.headers().firstValue("set-cookie").orElseThrow();
    assertTrue(cookie.contains("Secure"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    assertFalse(cookie.toLowerCase().contains("domain="));
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/v1/session")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/v1/session"))
                    .header("Cookie", cookie.split(";", 2)[0])
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/v1/session"))
                    .header("Cookie", "rag_session=invalid")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void foreignMissingAndRewrittenLocalOriginsCannotCreateSession() throws Exception {
    String valid = token("org-main", 300);
    assertEquals(403, exchange(valid, "https://other.example.invalid").statusCode());
    assertEquals(403, exchange(valid, base).statusCode());
    assertEquals(403, exchange(valid, null).statusCode());
    assertEquals(401, exchange(token("other-workspace", 300), ORIGIN).statusCode());
    assertEquals(401, exchange(token("org-main", -1), ORIGIN).statusCode());
    HttpRequest duplicate =
        HttpRequest.newBuilder(URI.create(base + "/v1/session"))
            .header("Origin", ORIGIN)
            .header("Origin", ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + valid + "\"}"))
            .build();
    assertEquals(403, client.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void logoutCookieIsSecureAndReadinessStillReportsRealMigrationGate() throws Exception {
    HttpResponse<String> logout =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/v1/session"))
                .header("Origin", ORIGIN)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, logout.statusCode());
    assertTrue(logout.headers().firstValue("set-cookie").orElseThrow().contains("Secure"));
    assertEquals(
        503,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/health/ready")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    HttpResponse<String> policy =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/health/entry-policy")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, policy.statusCode());
    assertTrue(policy.body().contains(ORIGIN));
    assertTrue(policy.body().contains("external_preview"));
  }

  @Test
  void startupRejectsHeaderAuthenticationPublicBackendAndInvalidOrigin() {
    RagProperties properties = context.getBean(RagProperties.class);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("rag.external-entry.enabled", "true")
            .withProperty("rag.external-entry.public-origin", ORIGIN)
            .withProperty("server.address", "0.0.0.0");
    assertThrows(IllegalArgumentException.class, () -> externalPolicy(environment, properties));
    environment.withProperty("server.address", "127.0.0.1");
    for (String invalid :
        new String[] {
          "http://knowledge.example.invalid",
          ORIGIN + "/",
          ORIGIN + "?x=1",
          "https://user@knowledge.example.invalid",
          ORIGIN + ":443"
        }) {
      environment.withProperty("rag.external-entry.public-origin", invalid);
      assertThrows(IllegalArgumentException.class, () -> externalPolicy(environment, properties));
    }
    environment.withProperty("rag.external-entry.public-origin", ORIGIN);
    RagProperties developmentHeaders =
        new RagProperties("test", "org-main", "development_headers", "", "", "", directory);
    assertThrows(
        IllegalArgumentException.class, () -> externalPolicy(environment, developmentHeaders));
  }

  private static ExternalEntryPolicy externalPolicy(
      MockEnvironment environment, RagProperties properties) {
    return new ExternalEntryPolicy(
        environment.getProperty("rag.external-entry.enabled", Boolean.class, false),
        environment.getProperty("rag.external-entry.public-origin", ""),
        environment.getProperty("server.address", "127.0.0.1"),
        properties.authMode());
  }
}
