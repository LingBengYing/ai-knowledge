package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvIndexResult(
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
    @JsonProperty("window_count") int windowCount,
    @JsonProperty("video_window_count") int videoWindowCount,
    @JsonProperty("audio_window_count") int audioWindowCount) {
  @Override
  public String toString() {
    return "VideoAvIndexResult[redacted]";
  }
}
