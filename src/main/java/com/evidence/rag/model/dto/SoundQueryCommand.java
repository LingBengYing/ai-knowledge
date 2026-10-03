package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import java.util.List;

/** Complete request-lifetime audio inputs, distinct from all persisted evidence. */
public record SoundQueryCommand(AnswerCommand answer, List<QueryAttachment> attachments) {
  public SoundQueryCommand {
    if (answer == null
        || attachments == null
        || attachments.isEmpty()
        || attachments.size() > 3
        || attachments.stream().anyMatch(a -> a == null || a.kind() != QueryAttachment.Kind.AUDIO)
        || attachments.stream().mapToLong(a -> a.content().length).sum() > 20L * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    attachments = List.copyOf(attachments);
  }

  @Override
  public String toString() {
    return "SoundQueryCommand[redacted]";
  }
}
