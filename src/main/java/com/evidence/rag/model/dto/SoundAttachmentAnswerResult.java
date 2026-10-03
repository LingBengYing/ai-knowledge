package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundAttachmentAnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String answer,
    @JsonProperty("reason_code") String reasonCode,
    List<SoundCitationResult> citations,
    @JsonProperty("policy_revision") String policyRevision,
    String mode,
    @JsonProperty("attachment_manifest") List<SoundQueryManifestResult> attachmentManifest) {
  public SoundAttachmentAnswerResult {
    citations = List.copyOf(citations);
    attachmentManifest = List.copyOf(attachmentManifest);
  }

  @Override
  public String toString() {
    return "SoundAttachmentAnswerResult[redacted]";
  }
}
