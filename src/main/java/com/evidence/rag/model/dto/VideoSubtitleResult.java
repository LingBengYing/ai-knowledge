package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Original subtitle packet identity, complete track coordinates and sealed native timing. */
public record VideoSubtitleResult(
    @JsonProperty("cue_id") String cueId,
    @JsonProperty("track_id") String trackId,
    @JsonProperty("stream_index") int streamIndex,
    String codec,
    String language,
    @JsonProperty("cue_ordinal") int cueOrdinal,
    long pts,
    long duration,
    @JsonProperty("time_base_numerator") long timeBaseNumerator,
    @JsonProperty("time_base_denominator") long timeBaseDenominator,
    @JsonProperty("start_code_point") int startCodePoint,
    @JsonProperty("end_code_point") int endCodePoint,
    String quote,
    @JsonProperty("quote_sha256") String quoteSha256,
    @JsonProperty("payload_sha256") String payloadSha256,
    @JsonProperty("subtitle_manifest_sha256") String subtitleManifestSha256,
    @JsonProperty("native_manifest_sha256") String nativeManifestSha256,
    @JsonProperty("track_text_sha256") String trackTextSha256,
    @JsonProperty("decoder_revision") String decoderRevision,
    @JsonProperty("text_format") String textFormat) {
  @Override
  public String toString() {
    return "VideoSubtitleResult[redacted]";
  }
}
