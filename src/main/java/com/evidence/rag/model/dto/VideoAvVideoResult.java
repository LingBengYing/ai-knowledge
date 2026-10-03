package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvVideoResult(
    @JsonProperty("clip_sha256") String clipSha256,
    @JsonProperty("frame_count") int frameCount,
    @JsonProperty("frames_manifest_sha256") String framesManifestSha256,
    @JsonProperty("first_local_tick") String firstLocalTick,
    @JsonProperty("end_local_tick") String endLocalTick) {
  @Override
  public String toString() {
    return "VideoAvVideoResult[redacted]";
  }
}
