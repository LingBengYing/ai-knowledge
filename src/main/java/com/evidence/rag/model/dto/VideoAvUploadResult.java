package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvUploadResult(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("source_revision_id") String sourceRevisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("size_bytes") long sizeBytes) {
  @Override
  public String toString() {
    return "VideoAvUploadResult[redacted]";
  }
}
