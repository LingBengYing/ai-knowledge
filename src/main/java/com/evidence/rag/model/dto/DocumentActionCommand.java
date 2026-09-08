package com.evidence.rag.model.dto;

import java.util.List;

public record DocumentActionCommand(
    List<String> documentIds,
    String action,
    boolean folderIdPresent,
    String folderId,
    List<String> tags) {
  public DocumentActionCommand {
    if (documentIds != null) {
      documentIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(documentIds));
    }
    if (tags != null) {
      tags = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tags));
    }
  }
}
