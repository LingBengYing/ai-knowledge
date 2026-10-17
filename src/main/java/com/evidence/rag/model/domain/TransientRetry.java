package com.evidence.rag.model.domain;

import java.time.Duration;

/**
 * Bounded in-task retry of transient conditions (embedding HTTP 429/5xx, timeouts, connection
 * errors, a busy parser or indexer). {@code retries} bounds the extra attempts of one task attempt
 * in total; a provider {@code Retry-After} is honoured exactly and never shortened. A wait longer
 * than {@code maxDelay} or than the task's remaining deadline ends the retry instead.
 */
public record TransientRetry(int retries, Duration baseDelay, Duration maxDelay) {
  public static final TransientRetry NONE =
      new TransientRetry(0, Duration.ofMillis(1), Duration.ofMillis(1));

  public TransientRetry {
    if (retries < 0
        || retries > 10
        || baseDelay == null
        || maxDelay == null
        || baseDelay.compareTo(Duration.ofMillis(1)) < 0
        || maxDelay.compareTo(baseDelay) < 0
        || maxDelay.compareTo(Duration.ofSeconds(600)) > 0) {
      throw new IllegalArgumentException("Invalid transient retry policy");
    }
  }

  /**
   * The wait before retry number {@code retry} (0-based): the provider's Retry-After when given,
   * otherwise exponential backoff from {@code baseDelay}. Null means the wait exceeds the policy.
   */
  public Duration delay(int retry, Duration retryAfter) {
    if (retryAfter != null) {
      return retryAfter.isNegative() || retryAfter.compareTo(maxDelay) > 0 ? null : retryAfter;
    }
    long millis = baseDelay.toMillis() << Math.min(Math.max(retry, 0), 20);
    return Duration.ofMillis(Math.min(millis, maxDelay.toMillis()));
  }
}
