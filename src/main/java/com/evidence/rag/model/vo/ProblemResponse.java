package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProblemResponse(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    @JsonProperty("error_code") String errorCode) {}
