package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvMode;

/** Complete original question plus explicit independent audiovisual mode. */
public record VideoAvAnswerCommand(AnswerCommand answer, VideoAvMode mode) {
  public VideoAvAnswerCommand {
    if (answer == null || mode == null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoAvAnswerCommand[redacted]";
  }
}
