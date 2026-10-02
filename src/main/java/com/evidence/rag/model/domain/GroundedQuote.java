package com.evidence.rag.model.domain;

import java.util.List;

/** A verified source fragment; start/end count code points in the authoritative context. */
public record GroundedQuote(
    String physicalId, int start, int end, String quote, List<String> factHashes) {
  public GroundedQuote {
    ModelValues.identifier(physicalId, 128);
    if (start < 0
        || end <= start
        || end - start > 1200
        || quote == null
        || quote.length() > 2400
        || quote.isBlank()
        || quote.codePointCount(0, quote.length()) != end - start
        || quote.codePoints().anyMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF))
        || factHashes == null
        || factHashes.isEmpty()
        || factHashes.size() > 8) {
      throw ModelValues.invalid();
    }
    for (String hash : factHashes) {
      if (hash == null || !hash.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
    factHashes = List.copyOf(factHashes);
  }

  @Override
  public String toString() {
    return "GroundedQuote[redacted]";
  }
}
