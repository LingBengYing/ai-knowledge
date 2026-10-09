package com.evidence.rag.model.entity;

/** One saved parsed source and immutable predecessor, never an original-file replacement. */
public record ModelRebuildItemEntity(
    String batchId,
    int ordinal,
    String documentId,
    String revisionId,
    String sourceSha256,
    String parserRevision,
    String basePublicationId,
    String baseVectorSetSha256,
    String jobId,
    String publicationId,
    String state) {
  @Override
  public String toString() {
    return "ModelRebuildItemEntity[redacted]";
  }
}
