package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Optional new embedding request limit, e.g. raised after the budget ran out. */
public record DocumentBatchResumeCommand(
    @JsonProperty("embedding_request_limit") Integer embeddingRequestLimit) {}
