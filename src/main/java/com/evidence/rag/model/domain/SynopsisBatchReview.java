package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Untrusted complete-content and per-final-item compatibility judgments for one raw batch. */
public record SynopsisBatchReview(boolean complete, List<ItemReview> items) {
  public record ItemReview(int index, boolean compatible) {
    public ItemReview {
      if (index < 0 || index >= 32) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisBatchReview.ItemReview[redacted]";
    }
  }

  public SynopsisBatchReview {
    if (items == null || items.isEmpty() || items.size() > 32) {
      throw ModelValues.invalid();
    }
    var indices = new HashSet<Integer>();
    for (var item : items) {
      if (item == null || !indices.add(item.index())) {
        throw ModelValues.invalid();
      }
    }
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "SynopsisBatchReview[redacted]";
  }
}
