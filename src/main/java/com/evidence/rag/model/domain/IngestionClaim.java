package com.evidence.rag.model.domain;

import static com.evidence.rag.model.domain.ModelValues.invalid;

public record IngestionClaim(
    String jobId,
    String documentId,
    String revisionId,
    String workspaceId,
    int attempt,
    String token,
    String filename,
    String mimeType,
    String parserRevision,
    byte[] content) {
  public IngestionClaim {
    if (content == null) {
      throw invalid();
    }
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public String toString() {
    return "IngestionClaim[redacted]";
  }
}
