package com.evidence.rag.model.dto;

import java.util.List;

public record DocumentResult(
    String documentId,
    String filename,
    String status,
    String activeRevisionId,
    String registeredRevisionId,
    int segmentCount,
    String updatedAt,
    String mimeType,
    long sizeBytes,
    String sha256,
    String displayName,
    String folderId,
    String folderName,
    List<String> tags,
    String currentRole,
    boolean canEdit,
    String indexStatus,
    TaskResult latestIndexJob,
    String indexPublicationId,
    boolean canIndex,
    TaskResult latestJob,
    boolean syntheticFixture,
    String documentType,
    boolean canReindex) {
  public DocumentResult(
      String documentId,
      String filename,
      String status,
      String activeRevisionId,
      String registeredRevisionId,
      int segmentCount,
      String updatedAt,
      String mimeType,
      long sizeBytes,
      String sha256,
      String displayName,
      String folderId,
      String folderName,
      List<String> tags,
      String currentRole,
      boolean canEdit,
      String indexStatus,
      TaskResult latestIndexJob,
      String indexPublicationId,
      boolean canIndex,
      TaskResult latestJob,
      boolean syntheticFixture,
      String documentType) {
    this(
        documentId,
        filename,
        status,
        activeRevisionId,
        registeredRevisionId,
        segmentCount,
        updatedAt,
        mimeType,
        sizeBytes,
        sha256,
        displayName,
        folderId,
        folderName,
        tags,
        currentRole,
        canEdit,
        indexStatus,
        latestIndexJob,
        indexPublicationId,
        canIndex,
        latestJob,
        syntheticFixture,
        documentType,
        false);
  }

  public DocumentResult {
    tags = List.copyOf(tags);
  }
}
