package com.evidence.rag.model.dto;

/** Existing typed answer shapes; no new JSON fields are introduced by this common contract. */
public sealed interface AnswerPayload
    permits AnswerResult, VisualAnswerResult, AudioAnswerResult, VideoAnswerResult {
  String answerId();

  String status();

  String answer();

  String reason();
}
