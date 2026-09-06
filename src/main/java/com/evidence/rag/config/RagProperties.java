package com.evidence.rag.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record RagProperties(
    String environment,
    String workspaceId,
    String authMode,
    String jwtSecret,
    String jwtIssuer,
    String jwtAudience,
    Path dataDirectory) {
  public RagProperties {
    if (!"development".equals(environment) && !"test".equals(environment)) {
      throw new IllegalArgumentException("Java production migration gates are incomplete");
    }
    if (!"development_headers".equals(authMode) && !"jwt".equals(authMode)) {
      throw new IllegalArgumentException("Unsupported authentication mode");
    }
    if (workspaceId == null
        || workspaceId.isBlank()
        || workspaceId.codePointCount(0, workspaceId.length()) > 200
        || workspaceId.codePoints().anyMatch(c -> c < 32 || c == 127)) {
      throw new IllegalArgumentException("Workspace must be explicit and bounded");
    }
    if (dataDirectory == null || Files.exists(dataDirectory.resolve("rag.db"))) {
      throw new IllegalArgumentException("Java edition requires an isolated data directory");
    }
    if ("jwt".equals(authMode)
        && (jwtSecret == null
            || jwtSecret.length() < 32
            || jwtSecret.startsWith("replace-")
            || jwtIssuer == null
            || jwtIssuer.isBlank()
            || jwtAudience == null
            || jwtAudience.isBlank())) {
      throw new IllegalArgumentException("JWT configuration is missing or unsafe");
    }
  }

  @Override
  public String toString() {
    return "RagProperties[configuration=redacted]";
  }
}
