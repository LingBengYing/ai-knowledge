package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentCleanupPageResult(
    List<DocumentCleanupResult> items,
    long total,
    int page,
    @JsonProperty("page_size") int pageSize) {
  public DocumentCleanupPageResult {
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "DocumentCleanupPageResult[redacted]";
  }
}
