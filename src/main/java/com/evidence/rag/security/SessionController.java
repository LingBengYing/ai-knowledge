package com.evidence.rag.security;

import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SessionController {
  private final AuthenticationModule authentication;

  public SessionController(AuthenticationModule authentication) {
    this.authentication = authentication;
  }

  @PostMapping("/v1/session")
  public ResponseEntity<Map<String, String>> create(
      @RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (body == null
        || body.size() != 1
        || !(body.get("token") instanceof String token)
        || token.isEmpty()
        || token.length() > AuthenticationModule.MAX_TOKEN_LENGTH) {
      throw new Problem(422, "invalid_session", "必须提供有效且长度受限的 token 字段。");
    }
    String normalized = token.strip();
    Actor actor = authentication.exchangeToken(normalized);
    return ResponseEntity.ok()
        .headers(cookieHeaders(normalized, false, request))
        .body(
            Map.of(
                "status",
                "authenticated",
                "workspace_id",
                actor.workspaceId(),
                "principal_id",
                actor.principalId()));
  }

  @DeleteMapping("/v1/session")
  public ResponseEntity<Map<String, String>> delete(HttpServletRequest request) {
    return ResponseEntity.ok()
        .headers(cookieHeaders("", true, request))
        .body(Map.of("status", "signed_out"));
  }

  private static HttpHeaders cookieHeaders(
      String token, boolean expired, HttpServletRequest request) {
    boolean secure =
        request.isSecure()
            || !loopback(request.getLocalAddr())
            || !loopback(request.getRemoteAddr());
    ResponseCookie.ResponseCookieBuilder cookie =
        ResponseCookie.from(AuthenticationModule.SESSION_COOKIE, token)
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict");
    if (expired) {
      cookie.maxAge(Duration.ZERO);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.SET_COOKIE, cookie.build().toString());
    headers.setCacheControl("private, no-store");
    return headers;
  }

  private static boolean loopback(String address) {
    return "127.0.0.1".equals(address)
        || "::1".equals(address)
        || "0:0:0:0:0:0:0:1".equals(address);
  }
}
