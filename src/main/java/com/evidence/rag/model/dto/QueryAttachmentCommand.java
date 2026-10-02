package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import java.util.List;

/** Request-lifetime query originals; the trusted actor remains a separate argument. */
public record QueryAttachmentCommand(
    AnswerCommand answer, QueryAnswerMode mode, List<QueryAttachment> attachments) {
  public QueryAttachmentCommand {
    if (answer == null
        || mode == null
        || attachments == null
        || attachments.size() > 3
        || attachments.stream().anyMatch(value -> value == null)
        || attachments.stream().mapToLong(value -> value.content().length).sum()
            > 20L * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    attachments = List.copyOf(attachments);
  }

  @Override
  public String toString() {
    return "QueryAttachmentCommand[redacted]";
  }
}
