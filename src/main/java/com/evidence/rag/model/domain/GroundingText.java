package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/**
 * Complete authority context and candidate code-point range, without a fabricated source locator.
 * Candidates from the same real page or transcript share its context identity and entire body;
 * adjacent unselected text remains available for conflict and applicability checks.
 */
public record GroundingText(
    String physicalId,
    String contextId,
    String contextText,
    String contextSha256,
    int startCodePoint,
    int endCodePoint) {
  public static final int MAX_CONTEXT_BYTES = 8 * 1024 * 1024;
  public static final int MAX_CANDIDATE_CODE_POINTS = 4096;

  public GroundingText {
    ModelValues.identifier(physicalId, 128);
    ModelValues.identifier(contextId, 256);
    if (contextText == null
        || contextText.length() > MAX_CONTEXT_BYTES
        || startCodePoint < 0
        || endCodePoint <= startCodePoint
        || endCodePoint - startCodePoint > MAX_CANDIDATE_CODE_POINTS
        || contextText.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))
        || endCodePoint > contextText.codePointCount(0, contextText.length())) {
      throw ModelValues.invalid();
    }
    byte[] encoded = contextText.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_CONTEXT_BYTES || !ModelValues.sha256(encoded).equals(contextSha256)) {
      throw ModelValues.invalid();
    }
  }

  public String snippet() {
    return contextText.substring(
        contextText.offsetByCodePoints(0, startCodePoint),
        contextText.offsetByCodePoints(0, endCodePoint));
  }

  @Override
  public String toString() {
    return "GroundingText[redacted]";
  }
}
