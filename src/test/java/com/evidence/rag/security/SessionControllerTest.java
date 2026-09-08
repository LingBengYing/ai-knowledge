package com.evidence.rag.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.SessionController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.vo.SessionResponse;
import com.evidence.rag.web.HttpProblemMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class SessionControllerTest {
  @TempDir Path directory;

  private SessionController controller(String mode) {
    return new SessionController(
        RequestAuthenticatorTest.verifier(
            RequestAuthenticatorTest.properties(directory, mode), RequestAuthenticatorTest.CLOCK));
  }

  private MockHttpServletRequest loopback() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setLocalAddr("127.0.0.1");
    request.setRemoteAddr("127.0.0.1");
    return request;
  }

  @Test
  void exchangeSetsSafeCookieAndOnlyReturnsValidatedIdentity() throws Exception {
    String token = RequestAuthenticatorTest.token(b -> {});
    ResponseEntity<SessionResponse> response =
        controller("jwt").create(Map.of("token", " " + token + " "), loopback());
    assertEquals(200, response.getStatusCode().value());
    assertEquals(
        Map.of("status", "authenticated", "workspace_id", "org-main", "principal_id", "owner"),
        new ObjectMapper().convertValue(response.getBody(), Map.class));
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
    String token = RequestAuthenticatorTest.token(b -> {});
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
    ResponseEntity<SessionResponse> response = controller("jwt").delete(loopback());
    assertEquals(
        Map.of("status", "signed_out"),
        new ObjectMapper().convertValue(response.getBody(), Map.class));
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
          HttpProblemMapper.status(
              assertThrows(
                  ApplicationException.class, () -> controller("jwt").create(body, loopback()))));
    }
    Map<String, Object> nullToken = new HashMap<>();
    nullToken.put("token", null);
    assertEquals(
        422,
        HttpProblemMapper.status(
            assertThrows(
                ApplicationException.class,
                () -> controller("jwt").create(nullToken, loopback()))));
    assertEquals(
        401,
        HttpProblemMapper.status(
            assertThrows(
                ApplicationException.class,
                () -> controller("jwt").create(Map.of("token", "invalid.jwt.value"), loopback()))));
  }

  @Test
  void explicitDevelopmentModeDoesNotAcceptTokenSessions() throws Exception {
    String token = RequestAuthenticatorTest.token(b -> {});
    assertEquals(
        401,
        HttpProblemMapper.status(
            assertThrows(
                ApplicationException.class,
                () ->
                    controller("development_headers").create(Map.of("token", token), loopback()))));
  }
}
