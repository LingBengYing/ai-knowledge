package com.evidence.rag.model.domain;

/** Immutable authentication inputs, redacted in diagnostics. */
public record AuthenticationOptions(
    String authMode, String workspaceId, String jwtSecret, String jwtIssuer, String jwtAudience) {
  @Override
  public String toString() {
    return "AuthenticationOptions[redacted]";
  }
}
