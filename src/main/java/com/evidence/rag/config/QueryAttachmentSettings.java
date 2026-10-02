package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent transport bounds; answer execution remains bounded by AnswersSettings. */
@ConfigurationProperties("rag.query-attachments")
public record QueryAttachmentSettings(boolean enabled, int receiveTimeoutMs, int maxConcurrent) {
  public QueryAttachmentSettings {
    if (receiveTimeoutMs < 10
        || receiveTimeoutMs > 60000
        || maxConcurrent < 1
        || maxConcurrent > 8) {
      throw new IllegalArgumentException("Invalid query attachment transport configuration");
    }
  }
}
