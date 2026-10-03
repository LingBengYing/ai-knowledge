package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentOriginalResponse(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    String filename,
    @JsonProperty("document_type") String documentType,
    @JsonProperty("media_type") String mediaType,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("size_bytes") long sizeBytes,
    @JsonProperty("content_url") String contentUrl) {}
