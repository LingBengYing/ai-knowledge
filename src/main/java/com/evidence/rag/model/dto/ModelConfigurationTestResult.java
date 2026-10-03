package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelConfigurationTestResult(
    long version, String role, String status, @JsonProperty("error_code") String errorCode) {}
