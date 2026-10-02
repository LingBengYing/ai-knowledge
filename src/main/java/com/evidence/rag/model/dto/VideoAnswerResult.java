package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Video answer released only after its complete-scope authority trace is committed. */
public record VideoAnswerResult(
    @JsonProperty("answer_id") String answerId,
    String status,
    String answer,
    String reason,
    List<VideoCitationResult> citations)
    implements AnswerPayload {
  public VideoAnswerResult {
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "VideoAnswerResult[redacted]";
  }
}
