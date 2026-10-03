package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import java.util.HashSet;
import java.util.List;

/** Explicit confirmation of server-owned candidates; no client-supplied label text is accepted. */
public record TagSuggestionApplyCommand(String suggestionFingerprint, List<Integer> ordinals) {
  public TagSuggestionApplyCommand {
    if (suggestionFingerprint == null
        || !suggestionFingerprint.matches("[a-f0-9]{64}")
        || ordinals == null
        || ordinals.isEmpty()
        || ordinals.size() > 8) {
      throw ModelValues.invalid();
    }
    var unique = new HashSet<Integer>();
    for (Integer ordinal : ordinals) {
      if (ordinal == null || ordinal < 1 || ordinal > 8 || !unique.add(ordinal)) {
        throw ModelValues.invalid();
      }
    }
    ordinals = List.copyOf(ordinals);
  }

  @Override
  public String toString() {
    return "TagSuggestionApplyCommand[redacted]";
  }
}
