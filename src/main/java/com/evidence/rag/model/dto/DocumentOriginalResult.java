package com.evidence.rag.model.dto;

/** Public original identity without saved bytes or any internal preparation state. */
public record DocumentOriginalResult(
    String documentId,
    String revisionId,
    String filename,
    String documentType,
    String mediaType,
    String sourceSha256,
    long sizeBytes) {}
