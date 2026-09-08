package com.evidence.rag.security.authentication;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AuthenticationOptions;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Validates signed identity independently of HTTP and configuration loading. */
public final class JwtAuthenticator {
  public static final int MAX_TOKEN_LENGTH = 16_384;
  private final AuthenticationOptions options;
  private final Clock clock;

  public JwtAuthenticator(AuthenticationOptions options, Clock clock) {
    this.options = options;
    this.clock = clock;
  }

  public Actor exchangeToken(String token) {
    if (!"jwt".equals(options.authMode())) {
      throw new ApplicationException(
          FailureKind.UNAUTHENTICATED, "unauthorized", "当前运行模式不接受浏览器 token 会话。");
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
          || !jwt.verify(new MACVerifier(options.jwtSecret().getBytes(StandardCharsets.UTF_8)))) {
        throw unauthorized();
      }
      Map<String, Object> claims = jwt.getPayload().toJSONObject();
      if (claims == null
          || !options.jwtIssuer().equals(claims.get("iss"))
          || !validAudience(claims.get("aud"))
          || !options.workspaceId().equals(claims.get("workspace_id"))
          || !(claims.get("sub") instanceof String principal)) {
        throw unauthorized();
      }
      long now = clock.instant().getEpochSecond();
      if (numericDate(claims.get("exp")) <= now
          || (claims.containsKey("nbf") && numericDate(claims.get("nbf")) > now)) {
        throw unauthorized();
      }
      return new Actor(options.workspaceId(), principal);
    } catch (Exception ignored) {
      // No original token, claims, library message, or exception cause crosses the Interface.
      throw unauthorized();
    }
  }

  private boolean validAudience(Object audience) {
    if (audience instanceof String value) {
      return options.jwtAudience().equals(value);
    }
    return audience instanceof List<?> values
        && values.stream().allMatch(String.class::isInstance)
        && values.contains(options.jwtAudience());
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

  private static ApplicationException unauthorized() {
    return new ApplicationException(
        FailureKind.UNAUTHENTICATED, "unauthorized", "Bearer token 无效或已过期。");
  }
}
