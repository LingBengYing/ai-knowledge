package com.evidence.rag.model.entity;

/** Read-only source directory row, not model context or an answer citation. */
public record WikiCatalogDocument(
    String documentId,
    String filename,
    String displayName,
    String mediaType,
    String kind,
    String state,
    String sourceRevisionId,
    String sourceSha256) {}
