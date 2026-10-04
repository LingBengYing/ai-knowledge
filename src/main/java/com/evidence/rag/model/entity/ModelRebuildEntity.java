package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.IndexTarget;

/** Frozen candidate configuration and its whole-library rebuilding operation. */
public record ModelRebuildEntity(
    String id, String workspaceId, String createdBy, TextRuntimeSelectionEntity baseSelection,
    long targetVersion, String configurationSha256, String anchorSha256, IndexTarget target,
    String state, int totalDocuments, int completedDocuments, String errorCode,
    String createdAt, String updatedAt) {
  @Override
  public String toString() {
    return "ModelRebuildEntity[redacted]";
  }
}
