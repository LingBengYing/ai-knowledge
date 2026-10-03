package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Reference preparation envelope around the unchanged seven-field library answer. */
public record VideoAvQueryAnswerResult(
    String mode,
    VideoAvAnswerResult result,
    @JsonProperty("query_attachments") List<VideoAvQueryAttachmentResult> queryAttachments) {
  public VideoAvQueryAnswerResult {
    queryAttachments = List.copyOf(queryAttachments);
  }

  @Override
  public String toString() {
    return "VideoAvQueryAnswerResult[redacted]";
  }
}
