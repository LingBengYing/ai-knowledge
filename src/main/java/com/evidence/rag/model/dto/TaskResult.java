package com.evidence.rag.model.dto;

/** Safe task data; indexing selects the existing task response variant at the Web adapter. */
public record TaskResult(
    String taskId,
    String documentId,
    String revisionId,
    String filename,
    String state,
    int attempt,
    String errorCode,
    String createdAt,
    String updatedAt,
    boolean canRetry,
    boolean canCancel,
    boolean indexing,
    String indexPublicationId) {}
