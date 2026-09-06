package com.evidence.rag.security;

import com.evidence.rag.config.RagProperties;
import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/** Identity Interface shared by HTTP bearer and browser-session Adapters. */
public final class AuthenticationModule {
  public static final String SESSION_COOKIE = "rag_session";
  public static final int MAX_TOKEN_LENGTH = 16_384;
  private final RagProperties properties;
  private final Clock clock;

  public AuthenticationModule(RagProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public Actor authenticate(HttpServletRequest request) {
    if ("development_headers".equals(properties.authMode())) {
      String host = request.getServerName();
      if (!"localhost".equalsIgnoreCase(host)
          && !"127.0.0.1".equals(host)
          && !"::1".equals(host)
          && !"[::1]".equals(host)
          && !"0:0:0:0:0:0:0:1".equals(host)) {
        throw new Problem(403, "development_host_denied", "开发身份仅允许本机访问。");
      }
      String workspace = singleHeader(request, "X-Workspace-Id", 422);
      String principal = singleHeader(request, "X-Principal-Id", 422);
      if (workspace == null || principal == null) {
        throw new Problem(422, "invalid_identity", "必须提供 X-Workspace-Id 与 X-Principal-Id。");
      }
      Actor actor = new Actor(workspace, principal);
      if (!properties.workspaceId().equals(actor.workspaceId())) {
        throw unauthorized();
      }
      return actor;
    }
    String authorization = singleHeader(request, "Authorization", 401);
    if (authorization != null) {
      if (!authorization.startsWith("Bearer ") || authorization.length() > MAX_TOKEN_LENGTH + 7) {
        throw unauthorized();
      }
      return exchangeToken(authorization.substring(7).strip());
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
    return exchangeToken(token == null ? null : token.strip());
  }

  public Actor exchangeToken(String token) {
    if (!"jwt".equals(properties.authMode())) {
      throw new Problem(401, "unauthorized", "当前运行模式不接受浏览器 token 会话。");
    }
    if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
      throw unauthorized();
    }
    try {
      String[] segments = token.split("\\.", -1);
      if (segments.length != 3) {
        throw unauthorized();
      }
      for (String segment : segments) {
        byte[] bytes = Base64.getUrlDecoder().decode(segment);
        if (segment.isEmpty()
            || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(segment)) {
          throw unauthorized();
        }
      }
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
          || jwt.getHeader().getCriticalParams() != null
          || !jwt.verify(
              new MACVerifier(properties.jwtSecret().getBytes(StandardCharsets.UTF_8)))) {
        throw unauthorized();
      }
      Map<String, Object> claims = jwt.getPayload().toJSONObject();
      if (claims == null
          || !properties.jwtIssuer().equals(claims.get("iss"))
          || !validAudience(claims.get("aud"))
          || !properties.workspaceId().equals(claims.get("workspace_id"))
          || !(claims.get("sub") instanceof String principal)) {
        throw unauthorized();
      }
      long now = clock.instant().getEpochSecond();
      if (numericDate(claims.get("exp")) <= now
          || (claims.containsKey("nbf") && numericDate(claims.get("nbf")) > now)) {
        throw unauthorized();
      }
      return new Actor(properties.workspaceId(), principal);
    } catch (Exception ignored) {
      // No original token, claims, library message, or exception cause crosses the Interface.
      throw unauthorized();
    }
  }

  private boolean validAudience(Object audience) {
    if (audience instanceof String value) {
      return properties.jwtAudience().equals(value);
    }
    return audience instanceof List<?> values
        && values.stream().allMatch(String.class::isInstance)
        && values.contains(properties.jwtAudience());
  }

  private static long numericDate(Object value) {
    if (value instanceof Long number) {
      return number;
    }
    if (value instanceof Integer number) {
      return number.longValue();
    }
    throw unauthorized();
  }

  private static String singleHeader(HttpServletRequest request, String name, int status) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) {
      return null;
    }
    String value = values.nextElement();
    if (values.hasMoreElements()) {
      throw new Problem(status, status == 401 ? "unauthorized" : "invalid_identity", "身份凭据无效。");
    }
    return value;
  }

  private static Problem unauthorized() {
    return new Problem(401, "unauthorized", "Bearer token 无效或已过期。");
  }
}
