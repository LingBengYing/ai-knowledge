package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit development-only indexing; never grants production readiness. */
@ConfigurationProperties("rag.indexing")
public record IndexingSettings(boolean enabled, int timeoutMs) {
  public IndexingSettings {
    if (timeoutMs < 10 || timeoutMs > 600000) {
      throw new IllegalArgumentException("Indexing deadline is invalid");
    }
  }
}
