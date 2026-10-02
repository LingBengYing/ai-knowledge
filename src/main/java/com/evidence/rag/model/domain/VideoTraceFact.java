package com.evidence.rag.model.domain;

/** Hash-only fact audit; support is discrete, never a calibrated probability. */
public record VideoTraceFact(
    int ordinal, String factSha256, int visualSupport, int transcriptSupport) {
  public VideoTraceFact {
    if (ordinal < 0
        || ordinal >= 8
        || factSha256 == null
        || !factSha256.matches("[a-f0-9]{64}")
        || visualSupport < 0
        || visualSupport > 1
        || transcriptSupport < 0
        || transcriptSupport > 1
        || visualSupport + transcriptSupport == 0) {
      throw ModelValues.invalid();
    }
  }
}
