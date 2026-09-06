package com.evidence.rag.security;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.shared.Problem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class SessionControllerTest {
  @TempDir Path directory;

  private SessionController controller(String mode) {
    return new SessionController(
        new AuthenticationModule(
            AuthenticationModuleTest.properties(directory, mode), AuthenticationModuleTest.CLOCK));
  }

  private MockHttpServletRequest loopback() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setLocalAddr("127.0.0.1");
    request.setRemoteAddr("127.0.0.1");
    return request;
  }

  @Test
  void exchangeSetsSafeCookieAndOnlyReturnsValidatedIdentity() throws Exception {
    String token = AuthenticationModuleTest.token(b -> {});
    ResponseEntity<Map<String, String>> response =
        controller("jwt").create(Map.of("token", " " + token + " "), loopback());
    assertEquals(200, response.getStatusCode().value());
    assertEquals(
        Map.of("status", "authenticated", "workspace_id", "org-main", "principal_id", "owner"),
        response.getBody());
    String cookie = response.getHeaders().getFirst("Set-Cookie");
    assertNotNull(cookie);
    assertTrue(cookie.startsWith("rag_session=" + token + ";"));
    assertTrue(cookie.contains("Path=/"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    assertFalse(cookie.contains("Secure"));
    assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
    assertFalse(response.getBody().toString().contains(token));
  }

  @Test
  void secureTransportAndNonLoopbackAlwaysUseSecureCookie() throws Exception {
    MockHttpServletRequest request = loopback();
    request.setSecure(true);
    String token = AuthenticationModuleTest.token(b -> {});
    assertTrue(
        controller("jwt")
            .create(Map.of("token", token), request)
            .getHeaders()
            .getFirst("Set-Cookie")
            .contains("Secure"));
    request.setSecure(false);
    request.setRemoteAddr("192.0.2.1");
    assertTrue(
        controller("jwt")
            .create(Map.of("token", token), request)
            .getHeaders()
            .getFirst("Set-Cookie")
            .contains("Secure"));
    request.setRemoteAddr("127.0.0.1");
    request.setLocalAddr("0.0.0.0");
    assertTrue(
        controller("jwt")
            .create(Map.of("token", token), request)
            .getHeaders()
            .getFirst("Set-Cookie")
            .contains("Secure"));
  }

  @Test
  void signOutExpiresTheSameCookieWithoutNeedingIdentity() {
    ResponseEntity<Map<String, String>> response = controller("jwt").delete(loopback());
    assertEquals(Map.of("status", "signed_out"), response.getBody());
    String cookie = response.getHeaders().getFirst("Set-Cookie");
    assertTrue(cookie.contains("rag_session=;"));
    assertTrue(cookie.contains("Max-Age=0"));
    assertTrue(cookie.contains("Path=/"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
  }

  @Test
  void invalidBodiesAndInvalidTokensNeverCreateSession() {
    for (Map<String, Object> body :
        java.util.List.<Map<String, Object>>of(
            Map.of(),
            Map.of("token", ""),
            Map.of("token", 123),
            Map.of("token", "a".repeat(16_385)),
            Map.of("token", "x", "principal_id", "owner"))) {
      assertEquals(
          422,
          assertThrows(Problem.class, () -> controller("jwt").create(body, loopback())).status());
    }
    Map<String, Object> nullToken = new HashMap<>();
    nullToken.put("token", null);
    assertEquals(
        422,
        assertThrows(Problem.class, () -> controller("jwt").create(nullToken, loopback()))
            .status());
    assertEquals(
        401,
        assertThrows(
                Problem.class,
                () -> controller("jwt").create(Map.of("token", "invalid.jwt.value"), loopback()))
            .status());
  }

  @Test
  void explicitDevelopmentModeDoesNotAcceptTokenSessions() throws Exception {
    String token = AuthenticationModuleTest.token(b -> {});
    assertEquals(
        401,
        assertThrows(
                Problem.class,
                () -> controller("development_headers").create(Map.of("token", token), loopback()))
            .status());
  }
}
