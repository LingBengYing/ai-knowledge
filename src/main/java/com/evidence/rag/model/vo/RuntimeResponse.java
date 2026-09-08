package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RuntimeResponse(
    @JsonProperty("auth_mode") String authMode,
    @JsonProperty("workspace_id") String workspaceId,
    String edition,
    @JsonProperty("migration_stage") String migrationStage,
    List<String> capabilities,
    List<String> unavailable) {
  public RuntimeResponse {
    capabilities = List.copyOf(capabilities);
    unavailable = List.copyOf(unavailable);
  }
}
