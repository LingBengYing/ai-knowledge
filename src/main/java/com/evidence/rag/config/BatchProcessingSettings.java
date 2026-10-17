package com.evidence.rag.config;

import com.evidence.rag.model.domain.IndexDeadline;
import com.evidence.rag.model.domain.TransientRetry;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded pipeline settings; queued work stays in SQLite, never in an unbounded executor. */
@ConfigurationProperties("rag.batch")
public record BatchProcessingSettings(
    @DefaultValue("2") int parseConcurrency,
    @DefaultValue("2") int indexConcurrency,
    @DefaultValue("2") int uploadConcurrency,
    @DefaultValue("10000") int queueCapacity,
    @DefaultValue("3") int transientRetries,
    @DefaultValue("1000") int retryBaseDelayMs,
    @DefaultValue("60000") int retryMaxDelayMs,
    @DefaultValue("5000") int indexTimeoutPerBatchMs,
    @DefaultValue("600000") int indexTimeoutMaxMs) {
  public BatchProcessingSettings {
    if (parseConcurrency < 1
        || parseConcurrency > 4
        || indexConcurrency < 1
        || indexConcurrency > 4
        || uploadConcurrency < 1
        || uploadConcurrency > 4
        || queueCapacity < 32
        || queueCapacity > 10000
        || transientRetries < 0
        || transientRetries > 10
        || retryBaseDelayMs < 1
        || retryBaseDelayMs > 60000
        || retryMaxDelayMs < retryBaseDelayMs
        || retryMaxDelayMs > 600000
        || indexTimeoutPerBatchMs < 0
        || indexTimeoutPerBatchMs > 60000
        || indexTimeoutMaxMs < 10
        || indexTimeoutMaxMs > 600000) {
      throw new IllegalArgumentException("Invalid batch processing limits");
    }
  }

  public TransientRetry transientRetry() {
    return new TransientRetry(
        transientRetries, Duration.ofMillis(retryBaseDelayMs), Duration.ofMillis(retryMaxDelayMs));
  }

  /** The configured base indexing deadline plus time per embedding request, up to the cap. */
  public IndexDeadline indexDeadline(Duration base) {
    var cap = Duration.ofMillis(indexTimeoutMaxMs);
    return new IndexDeadline(
        base, Duration.ofMillis(indexTimeoutPerBatchMs), cap.compareTo(base) < 0 ? base : cap);
  }
}
