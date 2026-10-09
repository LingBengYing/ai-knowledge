package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Ephemeral search result; its identity is not an answer trace or a source authorization token. */
public record ProductHelpResult(
    @JsonProperty("search_id") String searchId,
    @JsonProperty("configuration_version") long configurationVersion,
    String status,
    String reason,
    @JsonProperty("scope_count") int scopeCount,
    @JsonProperty("score_kind") String scoreKind,
    List<ProductHelpMatch> matches) {
  public ProductHelpResult {
    ModelValues.identifier(searchId, 36);
    if (configurationVersion < 1
        || configurationVersion > 9_007_199_254_740_991L
        || scopeCount < 0
        || !"rrf".equals(scoreKind)
        || matches == null
        || matches.size() > 20
        || !("completed".equals(status) || "empty".equals(status))) {
      throw ModelValues.invalid();
    }
    if ("completed".equals(status)) {
      if (reason != null || matches.isEmpty() || scopeCount == 0) {
        throw ModelValues.invalid();
      }
    } else if (!matches.isEmpty()
        || reason == null
        || !Set.of("empty_scope", "no_matches").contains(reason)
        || ("empty_scope".equals(reason) != (scopeCount == 0))) {
      throw ModelValues.invalid();
    }
    var ranks = new HashMap<String, Integer>();
    for (var match : matches) {
      if (match == null
          || !Set.of("document", "video").contains(match.category())
          || match.rank() != ranks.merge(match.category(), 1, Integer::sum)
          || match.rank() > 10) {
        throw ModelValues.invalid();
      }
    }
    matches = List.copyOf(matches);
  }

  @Override
  public String toString() {
    return "ProductHelpResult[redacted]";
  }
}
