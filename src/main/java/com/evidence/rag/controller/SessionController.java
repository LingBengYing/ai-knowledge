package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.vo.SessionResponse;
import com.evidence.rag.security.authentication.JwtAuthenticator;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.security.web.ExternalEntryPolicy;
import com.evidence.rag.security.web.RequestAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SessionController {
  private final JwtAuthenticator authentication;
  private final ExternalEntryPolicy externalEntry;

  public SessionController(JwtAuthenticator authentication) {
    this(authentication, ExternalEntryPolicy.disabled());
  }

  @Autowired
  public SessionController(JwtAuthenticator authentication, ExternalEntryPolicy externalEntry) {
    this.authentication = authentication;
    this.externalEntry = externalEntry;
  }

  @GetMapping("/v1/session")
  public ResponseEntity<SessionResponse> current(HttpServletRequest request) {
    Actor actor = AuthenticatedActor.require(request);
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(new SessionResponse("authenticated", actor.workspaceId(), actor.principalId()));
  }

  @PostMapping("/v1/session")
  public ResponseEntity<SessionResponse> create(
      @RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (body == null
        || body.size() != 1
        || !(body.get("token") instanceof String token)
        || token.isEmpty()
        || token.length() > JwtAuthenticator.MAX_TOKEN_LENGTH) {
      throw new ApplicationException(
          FailureKind.INVALID_INPUT, "invalid_session", "必须提供有效且长度受限的 token 字段。");
    }
    String normalized = token.strip();
    Actor actor = authentication.exchangeToken(normalized);
    return ResponseEntity.ok()
        .headers(cookieHeaders(normalized, false, request))
        .body(new SessionResponse("authenticated", actor.workspaceId(), actor.principalId()));
  }

  @DeleteMapping("/v1/session")
  public ResponseEntity<SessionResponse> delete(HttpServletRequest request) {
    return ResponseEntity.ok()
        .headers(cookieHeaders("", true, request))
        .body(new SessionResponse("signed_out", null, null));
  }

  private HttpHeaders cookieHeaders(String token, boolean expired, HttpServletRequest request) {
    boolean secure =
        externalEntry.enabled()
            || request.isSecure()
            || !loopback(request.getLocalAddr())
            || !loopback(request.getRemoteAddr());
    ResponseCookie.ResponseCookieBuilder cookie =
        ResponseCookie.from(RequestAuthenticator.SESSION_COOKIE, token)
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
