package com.evidence.rag.security;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.config.RagProperties;
import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthenticationModuleTest {
  static final String SECRET =
      "isolated-test-only-signing-secret-at-least-64-characters-abcdefghijk";
  static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  @TempDir Path directory;

  static RagProperties properties(Path path, String mode) {
    return new RagProperties(
        "test", "org-main", mode, SECRET, "evidence-test", "evidence-browser", path);
  }

  static String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
    return token(change, JWSAlgorithm.HS256, SECRET);
  }

  static String token(Consumer<JWTClaimsSet.Builder> change, JWSAlgorithm algorithm, String key)
      throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer("evidence-test")
            .audience("evidence-browser")
            .subject("owner")
            .claim("workspace_id", "org-main")
            .expirationTime(Date.from(NOW.plusSeconds(120)))
            .notBeforeTime(Date.from(NOW.minusSeconds(1)));
    change.accept(claims);
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims.build());
    token.sign(new MACSigner(key));
    return token.serialize();
  }

  private AuthenticationModule jwt() {
    return new AuthenticationModule(properties(directory, "jwt"), CLOCK);
  }

  private AuthenticationModule development() {
    return new AuthenticationModule(properties(directory, "development_headers"), CLOCK);
  }

  private MockHttpServletRequest bearer(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", value);
    return request;
  }

  private void denied(AuthenticationModule module, MockHttpServletRequest request, int status) {
    Problem problem = assertThrows(Problem.class, () -> module.authenticate(request));
    assertEquals(status, problem.status());
    assertFalse(problem.toString().contains(SECRET));
    assertNull(problem.getCause());
  }

  @Test
  void authenticatesValidBearerAndDoesNotUseDevelopmentHeaders() throws Exception {
    MockHttpServletRequest request = bearer("Bearer " + token(b -> {}));
    request.addHeader("X-Workspace-Id", "evil");
    request.addHeader("X-Principal-Id", "impersonated");
    assertEquals(new Actor("org-main", "owner"), jwt().authenticate(request));
  }

  @Test
  void acceptsAudienceListAndOptionalNotBefore() throws Exception {
    String signed =
        token(b -> b.audience(List.of("other", "evidence-browser")).notBeforeTime(null));
    assertEquals("owner", jwt().exchangeToken(signed).principalId());
  }

  @Test
  void acceptsCookieOnlyWhenNoAuthorizationHeaderExists() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie("unrelated", "anything"), new Cookie("rag_session", token(b -> {})));
    assertEquals("owner", jwt().authenticate(request).principalId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Basic abc", "Bearer ", "bearer bad", "Bearer broken.token.value"})
  void invalidHeaderNeverFallsBackToValidCookie(String header) throws Exception {
    MockHttpServletRequest request = bearer(header);
    request.setCookies(new Cookie("rag_session", token(b -> {})));
    denied(jwt(), request, 401);
  }

  @Test
  void rejectsMissingCredentialsAndDuplicateCredentials() throws Exception {
    denied(jwt(), new MockHttpServletRequest(), 401);
    MockHttpServletRequest duplicate = bearer("Bearer " + token(b -> {}));
    duplicate.addHeader("Authorization", "Bearer " + token(b -> {}));
    denied(jwt(), duplicate, 401);
    MockHttpServletRequest cookies = new MockHttpServletRequest();
    cookies.setCookies(
        new Cookie("rag_session", token(b -> {})), new Cookie("rag_session", token(b -> {})));
    denied(jwt(), cookies, 401);
  }

  @Test
  void rejectsUnknownCookiesAndWhitespaceCookie() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("else", "token"));
    denied(jwt(), request, 401);
    request.setCookies(new Cookie("rag_session", " "));
    denied(jwt(), request, 401);
  }

  @Test
  void rejectsWrongSignatureAndAlgorithm() throws Exception {
    denied(jwt(), bearer("Bearer " + token(b -> {}, JWSAlgorithm.HS256, SECRET + "other")), 401);
    denied(jwt(), bearer("Bearer " + token(b -> {}, JWSAlgorithm.HS384, SECRET)), 401);
  }

  @Test
  void rejectsExpiryBoundaryAndFutureNotBefore() throws Exception {
    denied(jwt(), bearer("Bearer " + token(b -> b.expirationTime(Date.from(NOW)))), 401);
    denied(
        jwt(),
        bearer("Bearer " + token(b -> b.expirationTime(Date.from(NOW.minusSeconds(1))))),
        401);
    denied(jwt(), bearer("Bearer " + token(b -> b.expirationTime(null))), 401);
    denied(
        jwt(), bearer("Bearer " + token(b -> b.notBeforeTime(Date.from(NOW.plusSeconds(1))))), 401);
    assertEquals(
        "owner", jwt().exchangeToken(token(b -> b.notBeforeTime(Date.from(NOW)))).principalId());
  }

  @Test
  void rejectsIssuerAudienceAndOrganizationMismatch() throws Exception {
    denied(jwt(), bearer("Bearer " + token(b -> b.issuer("other"))), 401);
    denied(jwt(), bearer("Bearer " + token(b -> b.audience("other"))), 401);
    denied(jwt(), bearer("Bearer " + token(b -> b.audience((String) null))), 401);
    denied(jwt(), bearer("Bearer " + token(b -> b.claim("workspace_id", "other"))), 401);
    denied(jwt(), bearer("Bearer " + token(b -> b.claim("workspace_id", null))), 401);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "bad\nidentity", "bad\u007fidentity"})
  void rejectsInvalidSubjectWithoutEchoingIt(String subject) throws Exception {
    String signed = token(b -> b.subject(subject));
    Problem problem = assertThrows(Problem.class, () -> jwt().exchangeToken(signed));
    assertEquals(401, problem.status());
    assertFalse(problem.toString().contains(signed));
  }

  @Test
  void rejectsMissingAndOversizedSubject() throws Exception {
    denied(jwt(), bearer("Bearer " + token(b -> b.subject(null))), 401);
    denied(jwt(), bearer("Bearer " + token(b -> b.subject("a".repeat(201)))), 401);
  }

  @Test
  void rejectsNoncanonicalSegmentsAndOversizedToken() throws Exception {
    String signed = token(b -> {});
    String[] parts = signed.split("\\.");
    for (int index = 0; index < 3; index++) {
      String[] padded = parts.clone();
      padded[index] += "=";
      denied(jwt(), bearer("Bearer " + String.join(".", padded)), 401);
    }
    denied(jwt(), bearer("Bearer " + signed + ".tail"), 401);
    denied(jwt(), bearer("Bearer " + "a".repeat(16_385)), 401);
    denied(jwt(), bearer("Bearer .."), 401);
    denied(jwt(), bearer("Bearer $.ab.cd"), 401);
  }

  @Test
  void acceptsExplicitDevelopmentIdentityOnlyWithinOrganization() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Workspace-Id", "org-main");
    request.addHeader("X-Principal-Id", "editor");
    request.addHeader("Authorization", "deliberately ignored in explicit development mode");
    assertEquals(new Actor("org-main", "editor"), development().authenticate(request));
    request.removeHeader("X-Workspace-Id");
    request.addHeader("X-Workspace-Id", "other");
    denied(development(), request, 401);
  }

  @Test
  void developmentRequiresBothHeadersAndValidIdentity() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    denied(development(), request, 422);
    request.addHeader("X-Workspace-Id", "org-main");
    denied(development(), request, 422);
    request.addHeader("X-Principal-Id", " ");
    denied(development(), request, 422);
    request.removeHeader("X-Principal-Id");
    request.addHeader("X-Principal-Id", "bad\nidentity");
    denied(development(), request, 422);
  }

  @Test
  void rejectsDuplicateDevelopmentHeadersAndDevelopmentTokenExchange() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Workspace-Id", "org-main");
    request.addHeader("X-Principal-Id", "owner");
    request.addHeader("X-Principal-Id", "editor");
    denied(development(), request, 422);
    Problem error = assertThrows(Problem.class, () -> development().exchangeToken(token(b -> {})));
    assertEquals(401, error.status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"evil.example", "localhost.evil.example", "127.0.0.1.evil.example", "192.0.2.1"})
  void developmentRejectsNonLoopbackHostToPreventDnsRebinding(String host) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setServerName(host);
    request.addHeader("X-Workspace-Id", "org-main");
    request.addHeader("X-Principal-Id", "owner");
    denied(development(), request, 403);
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1"})
  void developmentAcceptsLoopbackHost(String host) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setServerName(host);
    request.addHeader("X-Workspace-Id", "org-main");
    request.addHeader("X-Principal-Id", "owner");
    assertEquals("owner", development().authenticate(request).principalId());
  }

  @Test
  void signedMalformedClaimsAndCriticalHeadersAreRejected() throws Exception {
    Map<String, Object> claims =
        new HashMap<>(
            Map.of(
                "iss",
                "evidence-test",
                "aud",
                "evidence-browser",
                "sub",
                "owner",
                "workspace_id",
                "org-main",
                "exp",
                NOW.getEpochSecond() + 120));
    for (Map.Entry<String, Object> invalid :
        List.<Map.Entry<String, Object>>of(
            Map.entry("exp", "9999999999"),
            Map.entry("exp", 9999999999.5),
            Map.entry("nbf", "0"),
            Map.entry("aud", List.of("evidence-browser", 123)),
            Map.entry("sub", 123))) {
      Map<String, Object> changed = new HashMap<>(claims);
      changed.put(invalid.getKey(), invalid.getValue());
      JWSObject signed = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(changed));
      signed.sign(new MACSigner(SECRET));
      denied(jwt(), bearer("Bearer " + signed.serialize()), 401);
    }
    JWSObject critical =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .criticalParams(java.util.Set.of("unsupported"))
                .customParam("unsupported", true)
                .build(),
            new Payload(claims));
    critical.sign(new MACSigner(SECRET));
    denied(jwt(), bearer("Bearer " + critical.serialize()), 401);
  }

  @Test
  void duplicateNullCookieCannotHideDuplicateCredential() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("rag_session", null), new Cookie("rag_session", token(b -> {})));
    denied(jwt(), request, 401);
  }
}
