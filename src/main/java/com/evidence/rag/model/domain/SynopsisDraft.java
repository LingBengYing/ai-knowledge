package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Untrusted model proposal. Its shape is validated here; source support is checked separately. */
public record SynopsisDraft(boolean refused, List<Item> items) {
  public enum Section {
    OVERVIEW,
    TOPIC,
    TERM,
    TIMELINE
  }

  public record Item(Section section, String text, List<String> evidenceIds) {
    public Item {
      if (section == null
          || evidenceIds == null
          || evidenceIds.isEmpty()
          || evidenceIds.size() > 8) {
        throw ModelValues.invalid();
      }
      SynopsisEvidence.requireText(text, 1024);
      var unique = new HashSet<String>();
      for (String id : evidenceIds) {
        ModelValues.identifier(id, 128);
        if (!unique.add(id)) {
          throw ModelValues.invalid();
        }
      }
      evidenceIds = List.copyOf(evidenceIds);
    }

    @Override
    public String toString() {
      return "SynopsisDraft.Item[redacted]";
    }
  }

  public SynopsisDraft {
    if (items == null || items.size() > 32 || (refused && !items.isEmpty())) {
      throw ModelValues.invalid();
    }
    for (var item : items) {
      if (item == null) {
        throw ModelValues.invalid();
      }
    }
    items = List.copyOf(items);
  }

  @Override
  public String toString() {
    return "SynopsisDraft[redacted]";
  }
}
