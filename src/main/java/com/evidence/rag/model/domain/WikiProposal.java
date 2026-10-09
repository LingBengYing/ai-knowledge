package com.evidence.rag.model.domain;

import java.util.Set;

/** A complete proposed revision. Review changes only its terminal status and review time. */
public record WikiProposal(
    String id,
    String pageId,
    long baseVersion,
    WikiContent before,
    WikiContent after,
    String generationMethod,
    String modelRevision,
    String policyRevision,
    String status,
    long createdAt,
    Long reviewedAt) {
  public WikiProposal {
    ModelValues.identifier(id, 128);
    ModelValues.identifier(pageId, 128);
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    if (baseVersion < 0
        || (baseVersion == 0) != (before == null)
        || after == null
        || generationMethod == null
        || !Set.of("extractive", "model").contains(generationMethod)
        || status == null
        || !Set.of("pending", "accepted", "dismissed").contains(status)
        || createdAt < 0
        || (status.equals("pending") != (reviewedAt == null))
        || (reviewedAt != null && reviewedAt < createdAt)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "WikiProposal[redacted]";
  }
}
