package com.evidence.rag.model.dto;

import java.util.List;

public record FolderListResult(List<FolderResult> items) {
  public FolderListResult {
    items = List.copyOf(items);
  }
}
