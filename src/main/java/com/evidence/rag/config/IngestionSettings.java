package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in development ingestion; these settings do not grant production capability. */
@ConfigurationProperties("rag.ingestion")
public record IngestionSettings(boolean enabled, int parseTimeoutMs, int uploadTimeoutMs) {
  public IngestionSettings {
    if (parseTimeoutMs < 10
        || parseTimeoutMs > 300000
        || uploadTimeoutMs < 10
        || uploadTimeoutMs > 30000) {
      throw new IllegalArgumentException("Ingestion limits are invalid");
    }
  }
}
