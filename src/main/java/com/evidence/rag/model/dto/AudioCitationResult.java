package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Server-cut audio source locator; text offsets and model-invented times are never public. */
public record AudioCitationResult(
    int number,
    String kind,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    String filename,
    @JsonProperty("media_type") String mediaType,
    @JsonProperty("start_ms") long startMs,
    @JsonProperty("end_ms") long endMs,
    String quote,
    @JsonProperty("quote_sha256") String quoteSha256,
    @JsonProperty("text_origin") String textOrigin,
    @JsonProperty("time_precision") String timePrecision,
    @JsonProperty("source_url") String sourceUrl,
    @JsonProperty("content_url") String contentUrl) {
  @Override
  public String toString() {
    return "AudioCitationResult[redacted]";
  }
}
