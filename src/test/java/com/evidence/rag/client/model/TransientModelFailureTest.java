package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class TransientModelFailureTest {
  @Test
  void onlyRateLimitsProviderErrorsTimeoutsAndBrokenConnectionsAreTransient() {
    assertTrue(new TextModels.Failure("model_http_failed", 429).isTransient());
    assertTrue(new TextModels.Failure("model_http_failed", 500).isTransient());
    assertTrue(new TextModels.Failure("model_http_failed", 503).isTransient());
    assertTrue(new TextModels.Failure("model_timeout").isTransient());
    assertTrue(new TextModels.Failure("model_transport_failed").isTransient());
    assertFalse(new TextModels.Failure("model_http_failed", 400).isTransient());
    assertFalse(new TextModels.Failure("model_http_failed", 401).isTransient());
    assertFalse(new TextModels.Failure("model_http_failed").isTransient());
    assertFalse(new TextModels.Failure("model_response_invalid").isTransient());
  }

  @Test
  void retryAfterIsBoundedOnTheFailure() {
    assertEquals(
        Duration.ofSeconds(3),
        new TextModels.Failure("model_http_failed", 429, Duration.ofSeconds(3)).retryAfter());
    assertNull(new TextModels.Failure("model_http_failed", 429, Duration.ofDays(2)).retryAfter());
    assertNull(
        new TextModels.Failure("model_http_failed", 429, Duration.ofSeconds(-1)).retryAfter());
  }

  @Test
  void retryAfterParsesSecondsAndHttpDatesAndIgnoresAnythingElse() {
    assertEquals(Duration.ZERO, ModelHttpTransport.retryAfter("0"));
    assertEquals(Duration.ofSeconds(17), ModelHttpTransport.retryAfter(" 17 "));
    assertEquals(Duration.ofSeconds(86400), ModelHttpTransport.retryAfter("86400"));
    assertNull(ModelHttpTransport.retryAfter("86401"));
    assertNull(ModelHttpTransport.retryAfter(null));
    assertNull(ModelHttpTransport.retryAfter("-1"));
    assertNull(ModelHttpTransport.retryAfter("1.5"));
    assertNull(ModelHttpTransport.retryAfter("soon"));
    String later =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30));
    var wait = ModelHttpTransport.retryAfter(later);
    assertTrue(
        wait.compareTo(Duration.ofSeconds(25)) > 0 && wait.compareTo(Duration.ofSeconds(31)) <= 0);
    assertEquals(Duration.ZERO, ModelHttpTransport.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT"));
    String tooLate =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusDays(2));
    assertNull(ModelHttpTransport.retryAfter(tooLate));
  }
}
