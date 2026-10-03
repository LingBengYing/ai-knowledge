package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Ephemeral recognized input, never an answer or a library source. */
public record VoiceQuestionResponse(
    String transcript,
    @JsonProperty("transcript_sha256") String transcriptSha256,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("decoder_revision") String decoderRevision,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("compiler_revision") String compilerRevision,
    @JsonProperty("duration_ms") long durationMs,
    @JsonProperty("policy_revision") String policyRevision) {
  @Override
  public String toString() {
    return "VoiceQuestionResponse[redacted]";
  }
}
