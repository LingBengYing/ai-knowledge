package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact text excerpt with the whole server-cut span time; no estimated word-level timestamps. */
public record VideoTranscriptResult(
    @JsonProperty("start_ms") long startMs,
    @JsonProperty("end_ms") long endMs,
    String quote,
    @JsonProperty("quote_sha256") String quoteSha256,
    @JsonProperty("text_origin") String textOrigin,
    @JsonProperty("time_precision") String timePrecision) {
  @Override
  public String toString() {
    return "VideoTranscriptResult[redacted]";
  }
}
