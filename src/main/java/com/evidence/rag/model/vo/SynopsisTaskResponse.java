package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Public task receipt; never contains the internal claim or stored source payload. */
public record SynopsisTaskResponse(
    @JsonProperty("task_id") String taskId,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("publication_id") String publicationId,
    String state,
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt) {}
