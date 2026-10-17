package com.evidence.rag.model.domain;

/** Immutable manifest entry and its authority-derived processing state. */
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
    String errorCode) {
  public record Manifest(String filename, long sizeBytes, String sourceSha256) {}
}
