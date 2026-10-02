package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Actual group time and modality-specific source, never a fabricated page or scene. */
public record VideoCitationResult(
    int number,
    String kind,
    @JsonProperty("proof_origin") String proofOrigin,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    String filename,
    @JsonProperty("media_type") String mediaType,
    @JsonProperty("group_id") String groupId,
    @JsonProperty("start_us") long startUs,
    @JsonProperty("end_us") long endUs,
    @JsonProperty("start_ms") BigDecimal startMs,
    @JsonProperty("end_ms") BigDecimal endMs,
    @JsonProperty("time_precision") String timePrecision,
    VideoFrameResult frame,
    VideoTranscriptResult transcript,
    @JsonProperty("source_url") String sourceUrl,
    @JsonProperty("content_url") String contentUrl,
    VideoOcrResult ocr,
    VideoSubtitleResult subtitle) {
  public VideoCitationResult(
      int number,
      String kind,
      String proofOrigin,
      String documentId,
      String revisionId,
      String sourceSha256,
      String parserRevision,
      String filename,
      String mediaType,
      String groupId,
      long startUs,
      long endUs,
      BigDecimal startMs,
      BigDecimal endMs,
      String timePrecision,
      VideoFrameResult frame,
      VideoTranscriptResult transcript,
      String sourceUrl,
      String contentUrl,
      VideoOcrResult ocr) {
    this(
        number,
        kind,
        proofOrigin,
        documentId,
        revisionId,
        sourceSha256,
        parserRevision,
        filename,
        mediaType,
        groupId,
        startUs,
        endUs,
        startMs,
        endMs,
        timePrecision,
        frame,
        transcript,
        sourceUrl,
        contentUrl,
        ocr,
        null);
  }

  public VideoCitationResult(
      int number,
      String kind,
      String proofOrigin,
      String documentId,
      String revisionId,
      String sourceSha256,
      String parserRevision,
      String filename,
      String mediaType,
      String groupId,
      long startUs,
      long endUs,
      BigDecimal startMs,
      BigDecimal endMs,
      String timePrecision,
      VideoFrameResult frame,
      VideoTranscriptResult transcript,
      String sourceUrl,
      String contentUrl) {
    this(
        number,
        kind,
        proofOrigin,
        documentId,
        revisionId,
        sourceSha256,
        parserRevision,
        filename,
        mediaType,
        groupId,
        startUs,
        endUs,
        startMs,
        endMs,
        timePrecision,
        frame,
        transcript,
        sourceUrl,
        contentUrl,
        null);
  }

  @Override
  public String toString() {
    return "VideoCitationResult[redacted]";
  }
}
