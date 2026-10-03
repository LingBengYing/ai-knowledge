package com.evidence.rag.security.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/** Explicit HTTPS browser origin; forwarded headers never establish this trust. */
public final class ExternalEntryPolicy {
  private final String publicOrigin;

  public ExternalEntryPolicy(boolean enabled, String origin, String address, String authMode) {
    if (!enabled) {
      if (!origin.isEmpty()) {
        throw new IllegalArgumentException("External origin requires explicit opt-in");
      }
      publicOrigin = "";
      return;
    }
    if (!"jwt".equals(authMode) || !loopback(address) || !validOrigin(origin)) {
      throw new IllegalArgumentException(
          "External entry requires JWT, loopback and exact HTTPS origin");
    }
    publicOrigin = origin;
  }

  private ExternalEntryPolicy() {
    publicOrigin = "";
  }

  public static ExternalEntryPolicy disabled() {
    return new ExternalEntryPolicy();
  }

  public boolean enabled() {
    return !publicOrigin.isEmpty();
  }

  public String publicOrigin() {
    return publicOrigin;
  }

  public boolean accepts(HttpServletRequest request, String origin) {
    return enabled()
        && publicOrigin.equals(origin)
        && loopback(request.getLocalAddr())
        && loopback(request.getRemoteAddr());
  }

  private static boolean validOrigin(String value) {
    try {
      URI uri = URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getHost().equals(uri.getHost().toLowerCase(java.util.Locale.ROOT))
          && uri.getUserInfo() == null
          && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null
          && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
          && uri.getPort() != 443
          && !value.contains("\\")
          && value.length() <= 255;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean loopback(String address) {
    return "127.0.0.1".equals(address)
        || "::1".equals(address)
        || "0:0:0:0:0:0:0:1".equals(address);
  }
}
