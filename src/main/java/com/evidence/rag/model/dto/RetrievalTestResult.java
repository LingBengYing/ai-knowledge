package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ephemeral retrieval test: no answer identity, trace persistence, or source link. */
public record RetrievalTestResult(
    @JsonProperty("test_id") String testId,
    @JsonProperty("configuration_version") long configurationVersion,
    String status,
    String reason,
    @JsonProperty("scope_count") int scopeCount,
    @JsonProperty("score_kind") String scoreKind,
    List<RetrievalTestMatch> matches) {
  public RetrievalTestResult {
    try {
      if (!UUID.fromString(testId).toString().equals(testId)) {
        throw ModelValues.invalid();
      }
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ModelValues.invalid();
    }
    if (configurationVersion < 1
        || configurationVersion > 9_007_199_254_740_991L
        || scopeCount < 0
        || scopeCount > 128
        || !"rrf".equals(scoreKind)
        || matches == null
        || matches.size() > 20
        || matches.stream().anyMatch(match -> match == null)
        || (!"completed".equals(status) && !"empty".equals(status))) {
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
    var unique = new HashSet<String>();
    for (int i = 0; i < matches.size(); i++) {
      var match = matches.get(i);
      if (match.rank() != i + 1
          || !unique.add(
              match.documentId()
                  + ":"
                  + match.revisionId()
                  + ":"
                  + match.page()
                  + ":"
                  + match.start()
                  + ":"
                  + match.end())) {
        throw ModelValues.invalid();
      }
    }
    matches = List.copyOf(matches);
  }

  @Override
  public String toString() {
    return "RetrievalTestResult[redacted]";
  }
}
