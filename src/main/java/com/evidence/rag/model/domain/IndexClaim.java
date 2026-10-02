package com.evidence.rag.model.domain;

import static com.evidence.rag.model.domain.ModelValues.indexIdentity;
import static com.evidence.rag.model.domain.ModelValues.invalid;

import java.util.Collection;
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
    List<ProjectionItem> items,
    String projectionGenerationId) {
  public IndexClaim {
    indexIdentity(projectionGenerationId);
    if (items == null || items.isEmpty() || items.size() > 4096 || target == null) {
      throw invalid();
    }
    items = List.copyOf(items);
  }

  /** Text callers supply real, validated locators; only recall content crosses the worker seam. */
  public IndexClaim(
      String jobId,
      String documentId,
      String revisionId,
      String workspaceId,
      int attempt,
      String token,
      String sourceSha256,
      String parserRevision,
      IndexTarget target,
      Collection<IndexSegment> segments,
      String projectionGenerationId) {
    this(
        jobId,
        documentId,
        revisionId,
        workspaceId,
        attempt,
        token,
        sourceSha256,
        parserRevision,
        target,
        segments == null ? null : segments.stream().map(ProjectionItem::fromText).toList(),
        projectionGenerationId);
  }

  @Override
  public String toString() {
    return "IndexClaim[redacted]";
  }
}
