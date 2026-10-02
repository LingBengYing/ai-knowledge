package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAssessment;

/** Explicit proof mode without allowing the caller to supply source or publication identities. */
public record VideoAnswerCommand(
    AnswerCommand answer, VideoAssessment.Mode mode, boolean ocr, boolean subtitle) {
  public VideoAnswerCommand(AnswerCommand answer, VideoAssessment.Mode mode, boolean ocr) {
    this(answer, mode, ocr, false);
  }

  public VideoAnswerCommand(AnswerCommand answer, VideoAssessment.Mode mode) {
    this(answer, mode, false);
  }

  public VideoAnswerCommand {
    if (answer == null || (ocr && subtitle) || (mode == null) != (ocr || subtitle)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoAnswerCommand[redacted]";
  }
}
