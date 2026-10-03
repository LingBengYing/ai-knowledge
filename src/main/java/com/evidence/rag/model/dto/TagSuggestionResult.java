package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TagSuggestions;
import java.util.List;

/** Current authorized suggestions and independently mutable document metadata. */
public record TagSuggestionResult(
    TagSuggestions suggestions, List<String> existingTags, boolean canApply) {
  public TagSuggestionResult {
    if (suggestions == null
        || existingTags == null
        || existingTags.stream().anyMatch(t -> t == null)) {
      throw ModelValues.invalid();
    }
    existingTags = List.copyOf(existingTags);
  }

  @Override
  public String toString() {
    return "TagSuggestionResult[redacted]";
  }
}
