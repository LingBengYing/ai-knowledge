package com.evidence.rag.model.domain;

public record SyntheticDocument(
    String documentId,
    String filename,
    String type,
    String mimeType,
    String revisionId,
    String sha256,
    long sizeBytes) {}
