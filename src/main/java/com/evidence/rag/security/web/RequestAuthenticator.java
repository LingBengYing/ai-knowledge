package com.evidence.rag.security.web;

import static com.evidence.rag.security.authentication.JwtAuthenticator.MAX_TOKEN_LENGTH;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.security.authentication.JwtAuthenticator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;

/** Extracts untrusted credentials before validating identity. */
public final class RequestAuthenticator {
  public static final String SESSION_COOKIE = "rag_session";
  private final String mode;
  private final String workspaceId;
  private final JwtAuthenticator jwt;

  public RequestAuthenticator(String mode, String workspaceId, JwtAuthenticator jwt) {
    this.mode = mode;
    this.workspaceId = workspaceId;
    this.jwt = jwt;
  }

  public Actor authenticate(HttpServletRequest request) {
    if ("development_headers".equals(mode)) {
      String host = request.getServerName();
      if (!"localhost".equalsIgnoreCase(host)
          && !"127.0.0.1".equals(host)
          && !"::1".equals(host)
          && !"[::1]".equals(host)
          && !"0:0:0:0:0:0:0:1".equals(host)) {
        throw new ApplicationException(
            FailureKind.FORBIDDEN, "development_host_denied", "开发身份仅允许本机访问。");
      }
      String workspace = singleHeader(request, "X-Workspace-Id", false);
      String principal = singleHeader(request, "X-Principal-Id", false);
      if (workspace == null || principal == null) {
        throw new ApplicationException(
            FailureKind.INVALID_INPUT, "invalid_identity", "必须提供 X-Workspace-Id 与 X-Principal-Id。");
      }
      Actor actor = new Actor(workspace, principal);
      if (!workspaceId.equals(actor.workspaceId())) {
        throw unauthorized();
      }
      return actor;
    }
    String authorization = singleHeader(request, "Authorization", true);
    if (authorization != null) {
      if (!authorization.startsWith("Bearer ") || authorization.length() > MAX_TOKEN_LENGTH + 7) {
        throw unauthorized();
      }
      return jwt.exchangeToken(authorization.substring(7).strip());
    }
    String token = null;
    boolean foundSession = false;
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (SESSION_COOKIE.equals(cookie.getName())) {
          if (foundSession) {
            throw unauthorized();
          }
          foundSession = true;
          token = cookie.getValue();
        }
      }
    }
    if (token != null && token.length() > MAX_TOKEN_LENGTH) {
      throw unauthorized();
    }
    return jwt.exchangeToken(token == null ? null : token.strip());
  }

  private static String singleHeader(HttpServletRequest request, String name, boolean bearer) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) {
      return null;
    }
    String value = values.nextElement();
    if (values.hasMoreElements()) {
      throw new ApplicationException(
          bearer ? FailureKind.UNAUTHENTICATED : FailureKind.INVALID_INPUT,
          bearer ? "unauthorized" : "invalid_identity",
          "身份凭据无效。");
    }
    return value;
  }

  private static ApplicationException unauthorized() {
    return new ApplicationException(
        FailureKind.UNAUTHENTICATED, "unauthorized", "Bearer token 无效或已过期。");
  }
}
