package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimeGuardTest {
  private RagProperties properties(String mode) {
    return new RagProperties(
        "test",
        "org-main",
        mode,
        "a".repeat(32),
        "issuer",
        "audience",
        Path.of("unused-test-data"));
  }

  @Test
  void developmentHeadersCannotBindPublicly() {
    assertDoesNotThrow(() -> RuntimeGuard.check(properties("development_headers"), "127.0.0.1"));
    assertDoesNotThrow(() -> RuntimeGuard.check(properties("development_headers"), "::1"));
    for (String address :
        new String[] {"0.0.0.0", "::", "localhost.example.org", "", "192.168.1.5"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> RuntimeGuard.check(properties("development_headers"), address));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimeGuard.check(properties("development_headers"), null));
    assertDoesNotThrow(() -> RuntimeGuard.check(properties("jwt"), "0.0.0.0"));
  }

  @Test
  void configurationNeverPrintsSigningSecrets() {
    assertFalse(properties("jwt").toString().contains("a".repeat(32)));
  }
}
