package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentActionReceiptResponse(
    @JsonProperty("document_id") String documentId, String status) {}
