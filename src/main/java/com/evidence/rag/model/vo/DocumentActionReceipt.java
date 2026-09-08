package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentActionReceipt(
    @JsonProperty("document_id") String documentId, String status) {}
