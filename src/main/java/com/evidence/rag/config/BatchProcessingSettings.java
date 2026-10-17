package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded pipeline settings; queued work stays in SQLite, never in an unbounded executor. */
@ConfigurationProperties("rag.batch")
public record BatchProcessingSettings(
    @DefaultValue("2") int parseConcurrency,
    @DefaultValue("2") int indexConcurrency,
    @DefaultValue("2") int uploadConcurrency,
    @DefaultValue("10000") int queueCapacity) {
  public BatchProcessingSettings {
    if (parseConcurrency < 1
        || parseConcurrency > 4
        || indexConcurrency < 1
        || indexConcurrency > 4
        || uploadConcurrency < 1
        || uploadConcurrency > 4
        || queueCapacity < 32
        || queueCapacity > 10000) {
      throw new IllegalArgumentException("Invalid batch processing limits");
    }
  }
}
