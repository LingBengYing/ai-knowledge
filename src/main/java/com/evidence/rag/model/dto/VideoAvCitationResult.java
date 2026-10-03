package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvCitationResult(
    int number,
    String kind,
    String mode,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("publication_id") String publicationId,
    @JsonProperty("profile_fingerprint") String profileFingerprint,
    @JsonProperty("decoder_revision") String decoderRevision,
    String filename,
    @JsonProperty("media_type") String mediaType,
    VideoAvEpochResult epoch,
    VideoAvWindowResult window,
    List<VideoAvFactResult> facts,
    @JsonProperty("facts_sha256") String factsSha256,
    @JsonProperty("analysis_model_revision") String analysisModelRevision,
    @JsonProperty("policy_revision") String policyRevision,
    @JsonProperty("time_precision") String timePrecision,
    @JsonProperty("source_url") String sourceUrl,
    @JsonProperty("content_url") String contentUrl) {
  public VideoAvCitationResult {
    facts = List.copyOf(facts);
  }

  @Override
  public String toString() {
    return "VideoAvCitationResult[redacted]";
  }
}
