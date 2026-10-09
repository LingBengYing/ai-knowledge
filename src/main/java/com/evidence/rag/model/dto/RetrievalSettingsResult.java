package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.RetrievalSettings;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Public settings snapshot; version is the next save's compare-and-set precondition. */
public record RetrievalSettingsResult(
    long version,
    @JsonProperty("search_method") String searchMethod,
    @JsonProperty("ranking_mode") String rankingMode,
    @JsonProperty("dense_weight") double denseWeight,
    @JsonProperty("top_k") int topK,
    @JsonProperty("score_threshold_enabled") boolean scoreThresholdEnabled,
    @JsonProperty("score_threshold") double scoreThreshold) {
  public static RetrievalSettingsResult from(RetrievalSettings settings) {
    return new RetrievalSettingsResult(
        settings.version(),
        settings.searchMethod(),
        settings.rankingMode(),
        settings.denseWeight(),
        settings.topK(),
        settings.scoreThresholdEnabled(),
        settings.scoreThreshold());
  }
}
