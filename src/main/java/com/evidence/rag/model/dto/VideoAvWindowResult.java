package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvWindowResult(
    String id,
    int ordinal,
    @JsonProperty("start_tick") String startTick,
    @JsonProperty("end_tick") String endTick,
    @JsonProperty("start_ms") long startMs,
    @JsonProperty("end_ms") long endMs,
    VideoAvVideoResult video,
    VideoAvAudioResult audio) {
  @Override
  public String toString() {
    return "VideoAvWindowResult[redacted]";
  }
}
