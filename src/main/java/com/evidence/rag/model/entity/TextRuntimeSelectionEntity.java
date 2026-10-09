package com.evidence.rag.model.entity;

/** Hash-only authority pointer; private credentials never enter SQLite. */
public record TextRuntimeSelectionEntity(
    boolean initialized,
    Long activeVersion,
    String configurationSha256,
    String anchorSha256,
    String batchId,
    String updatedAt) {
  @Override
  public String toString() {
    return "TextRuntimeSelectionEntity[redacted]";
  }
}
