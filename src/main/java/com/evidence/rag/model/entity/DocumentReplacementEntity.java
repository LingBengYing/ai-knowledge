package com.evidence.rag.model.entity;

/** A staged original belongs to an existing document; it is not its current source. */
public record DocumentReplacementEntity(
    String id,
    String documentId,
    String baseRevisionId,
    String basePublicationId,
    String candidateRevisionId,
    String pipeline,
    String state,
    String createdBy,
    String ingestionJobId,
    String indexJobId,
    String publicationId,
    String createdAt,
    String updatedAt) {
  @Override
  public String toString() {
    return "DocumentReplacementEntity[redacted]";
  }
}
