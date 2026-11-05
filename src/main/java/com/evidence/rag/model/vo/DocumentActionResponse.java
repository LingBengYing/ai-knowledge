package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

@com.fasterxml.jackson.annotation.JsonInclude(
    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DocumentActionResponse(
    @JsonProperty("document_id") String documentId,
    boolean ok,
    DocumentActionReceiptResponse receipt,
    @JsonProperty("error_code") String errorCode,
    String detail) {}
