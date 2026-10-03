package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.TagSuggestionApplyCommand;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict JSON selection mapping; candidate text and source identities remain server-owned. */
public final class TagSuggestionRequestMapper {
  private TagSuggestionRequestMapper() {}

  public static TagSuggestionApplyCommand command(Map<String, Object> body) {
    if (body == null
        || !body.keySet().equals(Set.of("suggestion_fingerprint", "ordinals"))
        || !(body.get("suggestion_fingerprint") instanceof String fingerprint)
        || !fingerprint.matches("[a-f0-9]{64}")
        || !(body.get("ordinals") instanceof List<?> values)
        || values.isEmpty()
        || values.size() > 8) {
      throw ModelValues.invalid();
    }
    var ordinals = new ArrayList<Integer>();
    var seen = new HashSet<Integer>();
    for (Object value : values) {
      if (!(value instanceof Integer ordinal) || ordinal < 1 || ordinal > 8 || !seen.add(ordinal)) {
        throw ModelValues.invalid();
      }
      ordinals.add(ordinal);
    }
    return new TagSuggestionApplyCommand(fingerprint, ordinals);
  }
}
