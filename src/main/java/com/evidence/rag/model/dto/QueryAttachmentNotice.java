package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Safe preparation notice: no filenames, source bytes, text, hashes or provider errors. */
public record QueryAttachmentNotice(
    int ordinal,
    @JsonProperty("media_kind") String mediaKind,
    String status,
    @JsonProperty("visual_sampled") boolean visualSampled,
    String reason) {
  @Override
  public String toString() {
    return "QueryAttachmentNotice[redacted]";
  }
}
