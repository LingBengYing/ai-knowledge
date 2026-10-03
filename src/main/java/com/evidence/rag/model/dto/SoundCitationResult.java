package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundCitationResult(
    int number,
    String kind,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("publication_id") String publicationId,
    @JsonProperty("profile_fingerprint") String profileFingerprint,
    @JsonProperty("decoder_revision") String decoderRevision,
    @JsonProperty("pcm_sha256") String pcmSha256,
    String filename,
    @JsonProperty("media_type") String mediaType,
    @JsonProperty("start_sample") long startSample,
    @JsonProperty("end_sample") long endSample,
    @JsonProperty("sample_rate") int sampleRate,
    @JsonProperty("start_ms") long startMs,
    @JsonProperty("end_ms") long endMs,
    List<String> facts,
    @JsonProperty("facts_sha256") String factsSha256,
    @JsonProperty("analysis_model_revision") String analysisModelRevision,
    @JsonProperty("policy_revision") String policyRevision,
    @JsonProperty("time_precision") String timePrecision,
    @JsonProperty("source_url") String sourceUrl,
    @JsonProperty("content_url") String contentUrl) {
  public SoundCitationResult {
    facts = List.copyOf(facts);
  }

  @Override
  public String toString() {
    return "SoundCitationResult[redacted]";
  }
}
