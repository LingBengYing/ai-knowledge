package com.evidence.rag.model.domain;

public record ProjectionAttempt(
    String documentId,
    String workspaceId,
    String sourceRevisionId,
    String sourceSha256,
    String generationId,
    String route,
    QualifiedProjectionTarget target,
    boolean writeIssued) {
  public ProjectionAttempt {
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(workspaceId, 200);
    ModelValues.identifier(sourceRevisionId, 128);
    ModelValues.identifier(generationId, 128);
    ModelValues.identifier(route, 64);
    if (sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || target == null
        || !workspaceId.equals(target.workspaceId())) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ProjectionAttempt[redacted]";
  }
}
