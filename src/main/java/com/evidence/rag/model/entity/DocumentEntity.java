package com.evidence.rag.model.entity;

public record DocumentEntity(
    String id,
    String workspaceId,
    String filename,
    String documentType,
    String mimeType,
    String registrationRevisionId,
    String sourceSha256,
    long sizeBytes,
    String updatedAt,
    String displayName,
    String folderId,
    String currentRole,
    String folderName) {}
