package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** One planned fact, proved against members of a single video group; not an authorization. */
public record VideoFactProof(
    String factId, List<String> visualClaims, List<GroundedQuote> transcriptQuotes) {
  public VideoFactProof {
    if (factId == null
        || !factId.matches("[a-f0-9]{64}")
        || visualClaims == null
        || transcriptQuotes == null
        || visualClaims.size() > 8
        || transcriptQuotes.size() > 32
        || (visualClaims.isEmpty() && transcriptQuotes.isEmpty())) {
      throw ModelValues.invalid();
    }
    for (String claim : visualClaims) {
      if (claim == null
          || claim.isBlank()
          || claim.codePointCount(0, claim.length()) > 1024
          || claim.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))) {
        throw ModelValues.invalid();
      }
    }
    if (new HashSet<>(visualClaims).size() != visualClaims.size()) {
      throw ModelValues.invalid();
    }
    for (var quote : transcriptQuotes) {
      if (quote == null || !quote.factHashes().equals(List.of(factId))) {
        throw ModelValues.invalid();
      }
    }
    visualClaims = List.copyOf(visualClaims);
    transcriptQuotes = List.copyOf(transcriptQuotes);
  }

  public int visualSupport() {
    return visualClaims.isEmpty() ? 0 : 1;
  }

  public int transcriptSupport() {
    return transcriptQuotes.isEmpty() ? 0 : 1;
  }

  @Override
  public String toString() {
    return "VideoFactProof[redacted]";
  }
}
