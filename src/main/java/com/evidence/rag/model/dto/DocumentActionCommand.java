package com.evidence.rag.model.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DocumentActionCommand(
    List<String> documentIds,
    String action,
    boolean folderIdPresent,
    String folderId,
    List<String> tags,
    Map<String, String> basePublicationIds) {
  public DocumentActionCommand(
      List<String> documentIds, String action, boolean folderIdPresent,
      String folderId, List<String> tags) {
    this(documentIds, action, folderIdPresent, folderId, tags, null);
  }

  public DocumentActionCommand {
    if (documentIds != null) {
      documentIds = Collections.unmodifiableList(new ArrayList<>(documentIds));
    }
    if (tags != null) {
      tags = Collections.unmodifiableList(new ArrayList<>(tags));
    }
    if (basePublicationIds != null) {
      basePublicationIds = Collections.unmodifiableMap(new LinkedHashMap<>(basePublicationIds));
    }
  }
}
