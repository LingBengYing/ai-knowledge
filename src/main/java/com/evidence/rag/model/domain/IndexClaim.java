package com.evidence.rag.model.domain;

import static com.evidence.rag.model.domain.ModelValues.indexIdentity;
import static com.evidence.rag.model.domain.ModelValues.invalid;

import java.util.List;

public record IndexClaim(
    String jobId,
    String documentId,
    String revisionId,
    String workspaceId,
    int attempt,
    String token,
    String sourceSha256,
    String parserRevision,
    IndexTarget target,
    List<IndexSegment> segments,
    String projectionGenerationId) {
  public IndexClaim {
    indexIdentity(projectionGenerationId);
    if (segments == null || segments.isEmpty() || segments.size() > 4096 || target == null) {
      throw invalid();
    }
    segments = List.copyOf(segments);
  }

  @Override
  public String toString() {
    return "IndexClaim[redacted]";
  }
}
