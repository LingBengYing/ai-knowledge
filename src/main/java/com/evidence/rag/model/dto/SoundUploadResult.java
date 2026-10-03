package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundUploadResult(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("source_revision_id") String sourceRevisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("size_bytes") long sizeBytes) {
  @Override
  public String toString() {
    return "SoundUploadResult[redacted]";
  }
}
