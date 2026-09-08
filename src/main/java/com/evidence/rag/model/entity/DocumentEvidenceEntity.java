package com.evidence.rag.model.entity;

public record DocumentEvidenceEntity(
    String activeRevisionId, String publicationId, String jobId, String state, int segmentCount) {}
