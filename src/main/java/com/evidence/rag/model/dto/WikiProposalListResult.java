package com.evidence.rag.model.dto;

import java.util.List;

public record WikiProposalListResult(
    List<WikiProposalResult> items, long total, int offset, int limit) {
  public WikiProposalListResult {
    items = List.copyOf(items);
  }
}
