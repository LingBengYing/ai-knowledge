package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MediaInfoResponse(
    @JsonProperty("mime_type") String mimeType,
    @JsonProperty("size_bytes") long sizeBytes,
    String sha256) {}
