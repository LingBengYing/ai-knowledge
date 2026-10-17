package com.evidence.rag.model.domain;

/**
 * Immutable manifest entry and its authority-derived processing state. A {@code duplicate} entry
 * names the live document it repeats, or the earlier ordinal of the same manifest (and that file's
 * document once it is uploaded).
 */
public record BatchImportFile(
    int ordinal,
    String filename,
    long sizeBytes,
    String sourceSha256,
    String state,
    String documentId,
    String revisionId,
    String ingestionTaskId,
    String indexingTaskId,
    String errorCode,
    String duplicateDocumentId,
    Integer duplicateOrdinal) {
  public record Manifest(String filename, long sizeBytes, String sourceSha256) {}
}
