package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.config.BatchProcessingSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BatchResilienceDomainTest {
  @Test
  void backoffDoublesUpToTheMaximumAndRetryAfterIsHonouredExactly() {
    var retry = new TransientRetry(3, Duration.ofSeconds(1), Duration.ofSeconds(5));
    assertEquals(Duration.ofSeconds(1), retry.delay(0, null));
    assertEquals(Duration.ofSeconds(2), retry.delay(1, null));
    assertEquals(Duration.ofSeconds(4), retry.delay(2, null));
    assertEquals(Duration.ofSeconds(5), retry.delay(3, null));
    assertEquals(Duration.ofSeconds(5), retry.delay(60, null));
    assertEquals(Duration.ofMillis(3500), retry.delay(0, Duration.ofMillis(3500)));
    assertEquals(Duration.ZERO, retry.delay(2, Duration.ZERO));
    // A provider asking for longer than the policy allows ends the retry instead.
    assertNull(retry.delay(0, Duration.ofSeconds(6)));
  }

  @Test
  void retryPolicyIsBounded() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TransientRetry(11, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TransientRetry(1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TransientRetry(1, Duration.ofSeconds(1), Duration.ofSeconds(601)));
    assertEquals(0, TransientRetry.NONE.retries());
  }

  @Test
  void indexDeadlineScalesWithEmbeddingRequestsUpToTheCap() {
    var deadline =
        new IndexDeadline(Duration.ofSeconds(120), Duration.ofSeconds(5), Duration.ofSeconds(600));
    assertEquals(Duration.ofSeconds(120), deadline.forRequests(0));
    assertEquals(Duration.ofSeconds(125), deadline.forRequests(1));
    assertEquals(Duration.ofSeconds(620 - 20), deadline.forRequests(96));
    assertEquals(Duration.ofSeconds(600), deadline.forRequests(97));
    assertEquals(Duration.ofSeconds(600), deadline.forRequests(Integer.MAX_VALUE / 1000));
    assertEquals(Duration.ofSeconds(120), deadline.forRequests(-4));
    assertEquals(Duration.ofSeconds(9), IndexDeadline.fixed(Duration.ofSeconds(9)).forRequests(50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IndexDeadline(
                Duration.ofSeconds(120), Duration.ofSeconds(5), Duration.ofSeconds(60)));
  }

  @Test
  void settingsDeriveThePolicyAndNeverCapBelowTheBaseDeadline() {
    var settings =
        new BatchProcessingSettings(
            2, 2, 2, 10000, 3, 1000, 60000, 5000, 60000, 2, 1, 0, 0, 10737418240L);
    assertEquals(
        new TransientRetry(3, Duration.ofSeconds(1), Duration.ofSeconds(60)),
        settings.transientRetry());
    var deadline = settings.indexDeadline(Duration.ofSeconds(120));
    assertEquals(Duration.ofSeconds(120), deadline.forRequests(1000));
    assertEquals(
        Duration.ofSeconds(60), settings.indexDeadline(Duration.ofSeconds(10)).forRequests(1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BatchProcessingSettings(
                2, 2, 2, 10000, 3, 1000, 999, 5000, 60000, 2, 1, 0, 0, 10737418240L));
  }
}
