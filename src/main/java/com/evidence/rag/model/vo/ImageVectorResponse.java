package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Safe current-profile metadata, with no image content or provider endpoint. */
public record ImageVectorResponse(
    String status,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("publication_id") String publicationId,
    @JsonProperty("source_revision_id") String sourceRevisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("profile_fingerprint") String profileFingerprint,
    @JsonProperty("model_revision") String modelRevision,
    int dimensions,
    @JsonProperty("vector_generation_id") String vectorGenerationId,
    @JsonProperty("manifest_sha256") String manifestSha256) {
  @Override
  public String toString() {
    return "ImageVectorResponse[redacted]";
  }
}
