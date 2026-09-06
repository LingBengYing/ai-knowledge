package com.evidence.rag.config;

/** Refuses configurations that would expose the development identity adapter. */
public final class RuntimeGuard {
  private RuntimeGuard() {}

  public static void check(RagProperties properties, String address) {
    if ("development_headers".equals(properties.authMode())
        && !"127.0.0.1".equals(address)
        && !"::1".equals(address)) {
      throw new IllegalArgumentException(
          "Development identity headers require a literal loopback bind address");
    }
  }
}
