package com.evidence.rag.model.domain;

/** An untrusted model-selected quote, never an authoritative source locator. */
public record GroundingQuote(String physicalId, String quote) {
  @Override
  public String toString() {
    return "GroundingQuote[redacted]";
  }
}
