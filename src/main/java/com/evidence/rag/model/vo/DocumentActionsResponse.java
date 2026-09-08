package com.evidence.rag.model.vo;

import java.util.List;

public record DocumentActionsResponse(List<DocumentActionResponse> items) {
  public DocumentActionsResponse {
    items = List.copyOf(items);
  }
}
