package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit local text-answer processing limits; not a production readiness switch. */
@ConfigurationProperties("rag.answers")
public record AnswersSettings(boolean enabled, int timeoutMs, int maxConcurrent) {
  public AnswersSettings {
    if (timeoutMs < 10 || timeoutMs > 600000 || maxConcurrent < 1 || maxConcurrent > 8) {
      throw new IllegalArgumentException("Answer processing limits are invalid");
    }
  }
}
