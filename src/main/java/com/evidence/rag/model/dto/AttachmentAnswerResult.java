package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.QueryTrace;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;

/** New opt-in response envelope; nested result keeps the original typed citation contract. */
public record AttachmentAnswerResult(
    String mode,
    AnswerPayload result,
    @JsonProperty("query_attachments") List<QueryAttachmentNotice> queryAttachments) {
  public AttachmentAnswerResult {
    queryAttachments = List.copyOf(queryAttachments);
  }

  public static AttachmentAnswerResult from(String mode, AnswerPayload result, QueryTrace trace) {
    return new AttachmentAnswerResult(
        mode,
        result,
        trace == null
            ? List.of()
            : trace.attachments().stream()
                .map(
                    item ->
                        new QueryAttachmentNotice(
                            item.ordinal(),
                            item.mediaKind().name().toLowerCase(Locale.ROOT),
                            trace.status(),
                            item.manifest() != null && item.manifest().visualSampled(),
                            trace.reasonCode()))
                .toList());
  }

  @Override
  public String toString() {
    return "AttachmentAnswerResult[redacted]";
  }
}
