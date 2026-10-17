package com.evidence.rag.config;

import com.evidence.rag.model.domain.EmbeddingThroughput;
import com.evidence.rag.model.domain.IndexDeadline;
import com.evidence.rag.model.domain.TransientRetry;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded pipeline settings; queued work stays in SQLite, never in an unbounded executor. */
@ConfigurationProperties("rag.batch")
public record BatchProcessingSettings(
    @DefaultValue("4") int parseConcurrency,
    @DefaultValue("4") int indexConcurrency,
    @DefaultValue("4") int uploadConcurrency,
    @DefaultValue("10000") int queueCapacity,
    @DefaultValue("3") int transientRetries,
    @DefaultValue("1000") int retryBaseDelayMs,
    @DefaultValue("60000") int retryMaxDelayMs,
    @DefaultValue("5000") int indexTimeoutPerBatchMs,
    @DefaultValue("600000") int indexTimeoutMaxMs,
    @DefaultValue("2") int mediaIndexConcurrency,
    @DefaultValue("4") int embeddingConcurrency,
    @DefaultValue("1000") int embeddingRequestsPerMinute,
    @DefaultValue("400000") int embeddingTokensPerMinute,
    @DefaultValue("10737418240") long workspaceOriginalBytes) {
  public BatchProcessingSettings {
    if (parseConcurrency < 1
        || parseConcurrency > 4
        || indexConcurrency < 1
        || indexConcurrency > 4
        || uploadConcurrency < 1
        || uploadConcurrency > 8
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
        || indexTimeoutMaxMs > 600000
        || mediaIndexConcurrency < 1
        || mediaIndexConcurrency > 4
        || embeddingConcurrency < 1
        || embeddingConcurrency > 8
        || embeddingRequestsPerMinute < 0
        || embeddingRequestsPerMinute > 100000
        || embeddingTokensPerMinute < 0
        || embeddingTokensPerMinute > 100000000
        || workspaceOriginalBytes < 1024L * 1024
        || workspaceOriginalBytes > 1024L * 1024 * 1024 * 1024) {
      throw new IllegalArgumentException("Invalid batch processing limits");
    }
  }

  /** In-flight embedding requests per document and the shared provider rate limits. */
  public EmbeddingThroughput embeddingThroughput() {
    return new EmbeddingThroughput(
        embeddingConcurrency, embeddingRequestsPerMinute, embeddingTokensPerMinute);
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
