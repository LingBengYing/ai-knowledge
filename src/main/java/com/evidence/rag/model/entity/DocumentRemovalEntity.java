package com.evidence.rag.model.entity;

/** Immutable removal request; retained history is not proof that physical cleanup completed. */
public record DocumentRemovalEntity(
    String documentId, String workspaceId, String requestedBy, String requestedAt) {
  @Override
  public String toString() {
    return "DocumentRemovalEntity[redacted]";
  }
}
