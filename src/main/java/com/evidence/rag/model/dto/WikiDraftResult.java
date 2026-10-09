package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WikiDraftResult(
    String id,
    String title,
    String body,
    long version,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt) {}
