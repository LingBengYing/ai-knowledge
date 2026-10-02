package com.evidence.rag.model.dto;

/** Public-safe task receipt; no worker claims or original evidence. */
public record SynopsisTaskResult(
    String taskId,
    String documentId,
    String publicationId,
    String state,
    String errorCode,
    String createdAt,
    String updatedAt) {}
