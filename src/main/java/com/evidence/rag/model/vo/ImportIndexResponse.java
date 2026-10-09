package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImportIndexResponse(
    String state,
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("task_id") String taskId) {}
