package com.evidence.rag.model.domain;

import java.util.List;

public record CleanupPage(List<DocumentCleanupState> items, long total, int page, int pageSize) {
  public CleanupPage {
    if (items == null
        || total < 0
        || page < 1
        || pageSize < 1
        || pageSize > 100
        || items.size() > pageSize) {
      throw ModelValues.invalid();
    }
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "CleanupPage[redacted]";
  }
}
