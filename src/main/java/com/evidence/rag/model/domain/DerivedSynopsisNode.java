package com.evidence.rag.model.domain;

import java.util.List;

/** An unproved derived candidate over a complete descendant range, never original evidence. */
public record DerivedSynopsisNode(
    String id, int fromOrdinal, int endOrdinal, List<SynopsisDraft.Item> items) {
  public DerivedSynopsisNode {
    ModelValues.identifier(id, 128);
    if (fromOrdinal < 0
        || endOrdinal <= fromOrdinal
        || endOrdinal > SynopsisFileInput.MAX_EVIDENCE
        || items == null
        || items.isEmpty()
        || items.size() > 16
        || items.stream().anyMatch(item -> item == null)) {
      throw ModelValues.invalid();
    }
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "DerivedSynopsisNode[redacted]";
  }
}
