package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvAnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String mode,
    String answer,
    @JsonProperty("reason_code") String reasonCode,
    List<VideoAvCitationResult> citations,
    @JsonProperty("policy_revision") String policyRevision) {
  public VideoAvAnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "VideoAvAnswerResult[redacted]";
  }
}
