package com.evidence.rag.model.domain;

public record CleanupClaim(
    String cleanupId, String documentId, String workspaceId, String claimToken) {
  public CleanupClaim {
    ModelValues.identifier(cleanupId, 100);
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(workspaceId, 200);
    ModelValues.identifier(claimToken, 200);
  }

  @Override
  public String toString() {
    return "CleanupClaim[redacted]";
  }
}
