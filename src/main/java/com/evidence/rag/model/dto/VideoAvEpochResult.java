package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvEpochResult(
    String pts,
    @JsonProperty("time_base_num") String timeBaseNum,
    @JsonProperty("time_base_den") String timeBaseDen,
    @JsonProperty("ticks_per_second") String ticksPerSecond) {
  @Override
  public String toString() {
    return "VideoAvEpochResult[redacted]";
  }
}
