package com.evidence.rag.model.dto;

import java.util.List;

/** Presence flags preserve absent, explicit null, and empty PATCH values. */
public record DocumentPatchCommand(
    boolean displayNamePresent,
    String displayName,
    boolean folderIdPresent,
    String folderId,
    boolean tagsPresent,
    List<String> tags) {
  public DocumentPatchCommand {
    if (tags != null) {
      tags = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tags));
    }
  }
}
