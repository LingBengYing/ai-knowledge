package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.TraceEvidence;

/** Persisted server locator bindings; never a response or a model-provided source locator. */
public record TraceCitationEntity(
    TraceEvidence evidence,
    String publicationId,
    String sourceSegmentId,
    int page,
    String textSha256,
    String pageSha256,
    String quoteSha256) {
  @Override
  public String toString() {
    return "TraceCitationEntity[redacted]";
  }
}
