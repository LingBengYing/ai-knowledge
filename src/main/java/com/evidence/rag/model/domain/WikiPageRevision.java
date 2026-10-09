package com.evidence.rag.model.domain;

/** Immutable version of a reviewed Wiki page; it is not original answer evidence. */
public record WikiPageRevision(
    String pageId,
    long version,
    WikiContent content,
    String modelRevision,
    String policyRevision,
    long createdAt) {
  public WikiPageRevision {
    ModelValues.identifier(pageId, 128);
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    if (version < 1 || content == null || createdAt < 0) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "WikiPageRevision[redacted]";
  }
}
