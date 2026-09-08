package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(
    String status,
    @JsonProperty("workspace_id") String workspaceId,
    @JsonProperty("principal_id") String principalId) {}
