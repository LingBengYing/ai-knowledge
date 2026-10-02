package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Explicit typed locator and authorized navigation links, never raw binary Domain objects. */
public record SynopsisSourceResponse(
    @JsonProperty("synopsis_id") String synopsisId,
    @JsonProperty("entry_ordinal") int entryOrdinal,
    @JsonProperty("source_ordinal") int sourceOrdinal,
    @JsonProperty("evidence_id") String evidenceId,
    String kind,
    String sha256,
    String filename,
    @JsonProperty("media_type") String mediaType,
    String text,
    @JsonProperty("proof_origin") String proofOrigin,
    @JsonProperty("time_precision") String timePrecision,
    Locator locator,
    @JsonProperty("content_url") String contentUrl,
    @JsonProperty("frame_url") String frameUrl) {
  public sealed interface Locator
      permits Page, Image, Audio, VideoFrame, VideoTranscript, VideoOcr, VideoSubtitle {}

  public record Region(
      @JsonProperty("start_code_point") int start,
      @JsonProperty("end_code_point") int end,
      int left,
      int top,
      int right,
      int bottom) {}

  public record Page(
      String type,
      int page,
      @JsonProperty("start_code_point") int start,
      @JsonProperty("end_code_point") int end,
      Integer width,
      Integer height,
      List<Region> regions)
      implements Locator {
    public Page {
      regions = List.copyOf(regions);
    }
  }

  public record Image(String type, int width, int height) implements Locator {}

  public record Audio(
      String type,
      @JsonProperty("span_ordinal") int spanOrdinal,
      @JsonProperty("start_us") long startUs,
      @JsonProperty("end_us") long endUs)
      implements Locator {}

  public record VideoFrame(
      String type,
      @JsonProperty("frame_id") String frameId,
      @JsonProperty("frame_ordinal") int frameOrdinal,
      @JsonProperty("start_us") long startUs,
      @JsonProperty("end_us") long endUs,
      int width,
      int height)
      implements Locator {}

  public record VideoTranscript(
      String type,
      @JsonProperty("span_id") String spanId,
      @JsonProperty("span_ordinal") int spanOrdinal,
      @JsonProperty("start_us") long startUs,
      @JsonProperty("end_us") long endUs)
      implements Locator {}

  public record VideoOcr(
      String type,
      @JsonProperty("frame_id") String frameId,
      @JsonProperty("frame_ordinal") int frameOrdinal,
      @JsonProperty("start_us") long startUs,
      @JsonProperty("end_us") long endUs,
      int width,
      int height,
      @JsonProperty("start_code_point") int start,
      @JsonProperty("end_code_point") int end,
      List<Region> regions)
      implements Locator {
    public VideoOcr {
      regions = List.copyOf(regions);
    }
  }

  public record VideoSubtitle(
      String type,
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
      @JsonProperty("start_us") long startUs,
      @JsonProperty("end_us") long endUs,
      @JsonProperty("start_code_point") int startCodePoint,
      @JsonProperty("end_code_point") int endCodePoint,
      @JsonProperty("payload_sha256") String payloadSha256,
      @JsonProperty("subtitle_manifest_sha256") String subtitleManifestSha256,
      @JsonProperty("native_manifest_sha256") String nativeManifestSha256,
      @JsonProperty("track_text_sha256") String trackTextSha256,
      @JsonProperty("decoder_revision") String decoderRevision,
      @JsonProperty("text_format") String textFormat)
      implements Locator {}

  @Override
  public String toString() {
    return "SynopsisSourceResponse[redacted]";
  }
}
