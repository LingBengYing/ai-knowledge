package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.IndexTarget;

/** Internal database snapshot. Claim hashes, provider identities and creator stay out of HTTP. */
public record TaskEntity(
    String id,
    String documentId,
    String revisionId,
    String workspaceId,
    String filename,
    String mimeType,
    String sourceSha256,
    String parserRevision,
    String state,
    int attempt,
    String claimTokenSha256,
    String createdBy,
    String errorCode,
    String createdAt,
    String updatedAt,
    String currentRole,
    IndexTarget target,
    String projectionGenerationId) {
  @Override
  public String toString() {
    return "TaskEntity[redacted]";
  }
}
