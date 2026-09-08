package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FolderResult(
    @JsonProperty("folder_id") String folderId,
    String name,
    @JsonProperty("document_count") long documentCount,
    @JsonProperty("can_edit") boolean canEdit) {}
