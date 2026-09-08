package com.evidence.rag.model.domain;

import java.util.List;
import java.util.Set;

/** Only supported results carry verified fragments; reasons contain no source or query text. */
public record GroundingResult(boolean supported, String reason, List<GroundedQuote> quotes) {
  private static final Set<String> REFUSAL_REASONS =
      Set.of(
          "unsupported_question",
          "incomplete_evidence",
          "conflicting_evidence",
          "unsafe_evidence",
          "invalid_quote");

  public GroundingResult {
    if (quotes == null
        || reason == null
        || (supported
            ? !reason.equals("supported") || quotes.isEmpty()
            : !REFUSAL_REASONS.contains(reason) || !quotes.isEmpty())) {
      throw ModelValues.invalid();
    }
    for (GroundedQuote quote : quotes) {
      if (quote == null) {
        throw ModelValues.invalid();
      }
    }
    quotes = List.copyOf(quotes);
  }

  @Override
  public String toString() {
    return "GroundingResult[supported=" + supported + ", reason=" + reason + "]";
  }
}
