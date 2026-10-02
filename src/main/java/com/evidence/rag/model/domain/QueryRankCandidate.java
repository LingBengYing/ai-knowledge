package com.evidence.rag.model.domain;

/** Authorized candidate material for auxiliary ranking, never a source locator or factual proof. */
public record QueryRankCandidate(String text, VisualImage image) {
  public QueryRankCandidate {
    if (text == null
        || text.isBlank()
        || text.length() > 16384
        || text.codePointCount(0, text.length()) > 8192
        || text.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "QueryRankCandidate[redacted]";
  }
}
