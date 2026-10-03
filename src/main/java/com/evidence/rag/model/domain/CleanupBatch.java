package com.evidence.rag.model.domain;

import java.util.List;

public record CleanupBatch(List<CleanupBatchItem> items, int total) {
  public CleanupBatch {
    if (items == null || items.isEmpty() || items.size() > 100 || total != items.size()) {
      throw ModelValues.invalid();
    }
    items = List.copyOf(items);
    if (items.stream().map(CleanupBatchItem::documentId).distinct().count() != total) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "CleanupBatch[redacted]";
  }
}
