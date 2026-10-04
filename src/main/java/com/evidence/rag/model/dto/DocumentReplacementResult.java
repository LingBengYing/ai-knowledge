package com.evidence.rag.model.dto;

/** Safe state of one candidate original, separate from the current document publication. */
public record DocumentReplacementResult(
    String documentId,
    String baseRevisionId,
    String basePublicationId,
    String candidateRevisionId,
    String pipeline,
    String state,
    String filename,
    String documentType,
    String mediaType,
    String sourceSha256,
    Long sizeBytes,
    TaskResult ingestionTask,
    TaskResult indexTask,
    boolean canUpload,
    boolean canIndex,
    String publicationId) {}
