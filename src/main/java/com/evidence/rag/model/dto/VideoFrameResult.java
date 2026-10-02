package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Original decoded frame with exact microsecond PTS and an authorized server content URL. */
public record VideoFrameResult(
    @JsonProperty("frame_us") long frameUs,
    @JsonProperty("frame_ms") BigDecimal frameMs,
    @JsonProperty("duration_us") long durationUs,
    @JsonProperty("frame_sha256") String frameSha256,
    int width,
    int height,
    @JsonProperty("media_type") String mediaType,
    String origin,
    @JsonProperty("content_url") String contentUrl) {
  @Override
  public String toString() {
    return "VideoFrameResult[redacted]";
  }
}
