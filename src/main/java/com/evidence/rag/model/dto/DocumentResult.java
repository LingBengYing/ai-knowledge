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
    boolean canReindex,
    ImportIndexResult autoIndex) {
  public DocumentResult {
    tags = List.copyOf(tags);
  }
}
