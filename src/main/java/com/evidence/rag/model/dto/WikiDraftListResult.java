package com.evidence.rag.model.dto;

import java.util.List;

public record WikiDraftListResult(List<WikiDraftResult> items, long total, int offset, int limit) {
  public WikiDraftListResult {
    items = List.copyOf(items);
  }
}
