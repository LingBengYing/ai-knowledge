package com.evidence.rag.model.dto;

import java.util.List;

public record DocumentCleanupBatchResult(List<DocumentCleanupBatchItemResult> items, int total) {
  public DocumentCleanupBatchResult {
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "DocumentCleanupBatchResult[redacted]";
  }
}
