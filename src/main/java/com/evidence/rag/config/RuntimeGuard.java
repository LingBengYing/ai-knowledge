package com.evidence.rag.config;

import org.springframework.core.env.Environment;

/** Refuses configurations that would expose the development identity adapter. */
public final class RuntimeGuard {
  private RuntimeGuard() {}

  public static void check(RagSettings properties, String address) {
    if ("development_headers".equals(properties.authMode()) && !isLiteralLoopback(address)) {
      throw new IllegalArgumentException(
          "Development identity headers require a literal loopback bind address");
    }
  }

  /** Only the literal loopback addresses count; host names such as localhost are refused. */
  static boolean isLiteralLoopback(String address) {
    return "127.0.0.1".equals(address) || "::1".equals(address);
  }

  /**
   * A literal loopback bind in a development or test environment with every named {@code
   * rag.<module>.enabled} switch on: the precondition of the local-only model Modules.
   */
  static boolean isLocalDevelopment(Environment environment, String... enabledModules) {
    String mode = environment.getProperty("rag.environment", "development");
    if (!isLiteralLoopback(environment.getProperty("server.address"))
        || !("development".equals(mode) || "test".equals(mode))) {
      return false;
    }
    for (String module : enabledModules) {
      if (!environment.getProperty("rag." + module + ".enabled", Boolean.class, false)) {
        return false;
      }
    }
    return true;
  }
}
