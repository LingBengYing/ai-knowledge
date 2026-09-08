package com.evidence.rag.model.dto;

import java.util.List;

public record RuntimeCapabilities(
    String authMode,
    String workspaceId,
    String migrationStage,
    List<String> capabilities,
    List<String> unavailable) {
  public RuntimeCapabilities {
    capabilities = List.copyOf(capabilities);
    unavailable = List.copyOf(unavailable);
  }
}
