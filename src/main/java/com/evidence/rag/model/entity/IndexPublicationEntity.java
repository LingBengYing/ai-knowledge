package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.IndexTarget;

public record IndexPublicationEntity(
    String id,
    String jobId,
    String documentId,
    String revisionId,
    int attempt,
    String projectionGenerationId,
    String sourceSha256,
    String parserRevision,
    IndexTarget target,
    String manifestSha256,
    int segmentCount,
    String createdAt) {
  @Override
  public String toString() {
    return "IndexPublicationEntity[redacted]";
  }
}
