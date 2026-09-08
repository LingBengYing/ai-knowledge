package com.evidence.rag.model.dto;

import java.util.List;

public record DocumentPageResult(
    List<DocumentResult> items, long total, int page, int pageSize, long totalPages) {
  public DocumentPageResult {
    items = List.copyOf(items);
  }
}
