package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent receive, processing and admission bounds for optional voice input. */
@ConfigurationProperties("rag.voice-questions")
public record VoiceQuestionSettings(
    boolean enabled, int receiveTimeoutMs, int processingTimeoutMs, int maxConcurrent) {
  public VoiceQuestionSettings {
    if (receiveTimeoutMs < 10
        || receiveTimeoutMs > 60000
        || processingTimeoutMs < 10
        || processingTimeoutMs > 120000
        || maxConcurrent < 1
        || maxConcurrent > 8) {
      throw new IllegalArgumentException("Invalid voice question transport configuration");
    }
  }
}
