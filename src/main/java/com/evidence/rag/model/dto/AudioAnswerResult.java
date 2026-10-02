package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Public audio answer, released only after the authoritative trace commit. */
public record AudioAnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String answer,
    String reason,
    List<AudioCitationResult> citations)
    implements AnswerPayload {
  public AudioAnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "AudioAnswerResult[redacted]";
  }
}
