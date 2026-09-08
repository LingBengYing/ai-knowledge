package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentPageResponse(
    List<DocumentResponse> items,
    long total,
    int page,
    @JsonProperty("page_size") int pageSize,
    @JsonProperty("total_pages") long totalPages) {
  public DocumentPageResponse {
    items = List.copyOf(items);
  }
}
