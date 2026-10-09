package com.evidence.rag.model.dto;

import java.util.List;

public record WikiPageListResult(List<WikiPageResult> items, long total, int offset, int limit) {
  public WikiPageListResult {
    items = List.copyOf(items);
  }
}
