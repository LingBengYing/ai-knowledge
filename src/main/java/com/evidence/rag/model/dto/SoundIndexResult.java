package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundIndexResult(
    String status,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("source_revision_id") String sourceRevisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("profile_fingerprint") String profileFingerprint,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("embedding_model_revision") String embeddingModelRevision,
    int dimensions,
    @JsonProperty("publication_id") String publicationId,
    @JsonProperty("generation_id") String generationId,
    @JsonProperty("manifest_sha256") String manifestSha256,
    @JsonProperty("span_count") int spanCount) {
  @Override
  public String toString() {
    return "SoundIndexResult[redacted]";
  }
}
