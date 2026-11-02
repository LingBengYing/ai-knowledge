package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public metadata of one stored sound or video audiovisual original; no media bytes. */
public record OriginalUploadResult(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("source_revision_id") String sourceRevisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("size_bytes") long sizeBytes) {
  public static OriginalUploadResult from(DocumentOriginal original) {
    return new OriginalUploadResult(
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.sizeBytes());
  }

  @Override
  public String toString() {
    return "OriginalUploadResult[redacted]";
  }
}
