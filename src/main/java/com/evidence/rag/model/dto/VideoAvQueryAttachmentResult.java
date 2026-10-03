package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.VideoAvQueryManifest;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public whitelist of complete preparation receipts; no query bytes or filenames. */
public record VideoAvQueryAttachmentResult(
    int ordinal,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("media_kind") String mediaKind,
    @JsonProperty("compiler_revision") String compilerRevision,
    @JsonProperty("content_sha256") String contentSha256,
    @JsonProperty("window_count") Integer windowCount,
    @JsonProperty("visual_window_count") Integer visualWindowCount,
    @JsonProperty("audio_window_count") Integer audioWindowCount,
    @JsonProperty("audio_present") Boolean audioPresent,
    @JsonProperty("used_mode") String usedMode,
    String status) {
  public static VideoAvQueryAttachmentResult from(VideoAvQueryManifest manifest) {
    return new VideoAvQueryAttachmentResult(
        manifest.ordinal(),
        manifest.sourceSha256(),
        manifest.mediaKind(),
        manifest.compilerRevision(),
        manifest.contentSha256(),
        manifest.windowCount(),
        manifest.visualWindowCount(),
        manifest.audioWindowCount(),
        manifest.audioPresent(),
        manifest.usedMode().name(),
        manifest.status());
  }

  @Override
  public String toString() {
    return "VideoAvQueryAttachmentResult[redacted]";
  }
}
