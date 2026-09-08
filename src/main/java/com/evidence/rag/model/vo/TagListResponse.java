package com.evidence.rag.model.vo;

import java.util.List;

public record TagListResponse(List<String> items) {
  public TagListResponse {
    items = List.copyOf(items);
  }
}
