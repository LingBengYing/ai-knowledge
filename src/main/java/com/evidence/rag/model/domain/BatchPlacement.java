package com.evidence.rag.model.domain;

/** Optional, immutable placement chosen when registering an import. */
public record BatchPlacement(
    String targetFolderId, String targetFolderName, boolean preservePaths) {
  public static final BatchPlacement NONE = new BatchPlacement(null, null, false);
}
