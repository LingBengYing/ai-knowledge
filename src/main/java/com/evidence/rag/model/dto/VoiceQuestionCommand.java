package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;

/** One ephemeral audio original for user input preparation, never a library attachment. */
public record VoiceQuestionCommand(QueryAttachment audio) {
  public VoiceQuestionCommand {
    if (audio == null
        || audio.kind() != QueryAttachment.Kind.AUDIO
        || audio.content().length > 20 * 1024 * 1024) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VoiceQuestionCommand[redacted]";
  }
}
