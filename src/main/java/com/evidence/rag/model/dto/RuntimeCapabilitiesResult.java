package com.evidence.rag.model.dto;

import java.util.List;

public record RuntimeCapabilitiesResult(
    String authMode,
    String workspaceId,
    String migrationStage,
    List<String> capabilities,
    List<String> unavailable) {
  public RuntimeCapabilitiesResult {
    capabilities = List.copyOf(capabilities);
    unavailable = List.copyOf(unavailable);
  }
}
