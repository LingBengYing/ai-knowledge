package com.evidence.rag.model.query;

public record DocumentQuery(
    String q,
    String type,
    String status,
    String folderId,
    String tag,
    String sort,
    int page,
    int pageSize) {}
