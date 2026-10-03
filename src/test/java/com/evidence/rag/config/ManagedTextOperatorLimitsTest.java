package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

/** Operator typos fail locally and safely before any connection or model request. */
class ManagedTextOperatorLimitsTest {
  @ParameterizedTest
  @CsvSource({
    "deadline-ms,0",
    "deadline-ms,60001",
    "deadline-ms,not-a-duration",
    "max-response-bytes,1023",
    "max-response-bytes,4194305",
    "max-response-bytes,not-a-size",
    "provider-base-url,http://127.0.0.1:18084/v1",
    "provider-base-url,https://synthetic.invalid/v1?unexpected=1",
    "provider-base-url,https://synthetic.invalid/v1#unexpected"
  })
  void invalidTrustedProviderOrBudgetCannotBecomeAPartiallyWorkingSetup(
      String setting, String value) {
    var environment =
        new MockEnvironment()
            .withProperty("server.address", "127.0.0.1")
            .withProperty("rag.environment", "test")
            .withProperty("rag.model-configuration.enabled", "true")
            .withProperty("rag.model-configuration.administrators", "owner")
            .withProperty("rag.model-configuration." + setting, value);
    var failure =
        assertThrows(IllegalArgumentException.class, () -> new ManagedTextSettings(environment));
    assertEquals("Invalid managed text server configuration", failure.getMessage());
    assertNull(failure.getCause());
  }
}
