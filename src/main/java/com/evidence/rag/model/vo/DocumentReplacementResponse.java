package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentReplacementResponse(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("base_revision_id") String baseRevisionId,
    @JsonProperty("base_publication_id") String basePublicationId,
    @JsonProperty("candidate_revision_id") String candidateRevisionId,
    String pipeline,
    String state,
    String filename,
    @JsonProperty("document_type") String documentType,
    @JsonProperty("media_type") String mediaType,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("size_bytes") Long sizeBytes,
    @JsonProperty("ingestion_task") TaskResponse ingestionTask,
    @JsonProperty("index_task") TaskResponse indexTask,
    @JsonProperty("can_upload") boolean canUpload,
    @JsonProperty("can_index") boolean canIndex,
    @JsonProperty("publication_id") String publicationId) {}
