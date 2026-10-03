package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import java.util.List;

/** Complete temporary VIDEO inputs, independent of persisted originals and publications. */
public record VideoAvQueryCommand(VideoAvAnswerCommand answer, List<QueryAttachment> attachments) {
  public VideoAvQueryCommand {
    if (answer == null
        || attachments == null
        || attachments.isEmpty()
        || attachments.size() > 3
        || attachments.stream()
            .anyMatch(value -> value == null || value.kind() != QueryAttachment.Kind.VIDEO)
        || attachments.stream().mapToLong(value -> value.content().length).sum()
            > 20L * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    attachments = List.copyOf(attachments);
  }

  @Override
  public String toString() {
    return "VideoAvQueryCommand[redacted]";
  }
}
