package com.evidence.rag.service;

import com.evidence.rag.model.dto.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;

/** Publishes explicit local migration capabilities without exposing provider credentials. */
public final class RuntimeService {
  private final RuntimeCapabilities capabilities;

  public RuntimeService(String authMode, String workspaceId, boolean ingestion, boolean indexing) {
    this(authMode, workspaceId, ingestion, indexing, false);
  }

  public RuntimeService(
      String authMode, String workspaceId, boolean ingestion, boolean indexing, boolean answers) {
    var enabled =
        new ArrayList<>(List.of("management", "folders", "metadata", "batch_move", "batch_tag"));
    var unavailable = new ArrayList<>(List.of("answers", "sources", "reindex", "document_delete"));
    if (ingestion) {
      enabled.addAll(List.of("text_upload", "ingestions"));
    } else {
      unavailable.addAll(List.of("upload", "ingestions"));
    }
    if (indexing) {
      enabled.addAll(List.of("text_index", "indexings"));
    } else {
      unavailable.addAll(List.of("text_index", "indexings"));
    }
    if (answers) {
      enabled.addAll(List.of("answers", "sources"));
      unavailable.removeAll(List.of("answers", "sources"));
    }
    capabilities =
        new RuntimeCapabilities(
            authMode,
            workspaceId,
            answers
                ? "text_answers"
                : indexing ? "text_indexing" : ingestion ? "text_ingestion" : "management_slice",
            enabled,
            unavailable);
  }

  public RuntimeCapabilities capabilities() {
    return capabilities;
  }
}
