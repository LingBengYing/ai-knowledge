package com.evidence.rag.model.entity;

public record RevisionEntity(
    String id, String sourceSha256, String parserRevision, int segmentCount) {}
