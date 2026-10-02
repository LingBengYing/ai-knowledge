package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** A bounded consecutive set of derived nodes; only their original-id lineage may be retained. */
public record SynopsisReductionInput(
    PublicationVersion publication,
    String inputFingerprint,
    Stage stage,
    List<DerivedSynopsisNode> nodes) {
  public enum Stage {
    INTERMEDIATE,
    FINAL
  }

  public SynopsisReductionInput {
    if (publication == null
        || inputFingerprint == null
        || !inputFingerprint.matches("[a-f0-9]{64}")
        || stage == null
        || nodes == null
        || nodes.isEmpty()
        || nodes.size() > 3) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    int previous = -1;
    for (var node : nodes) {
      if (node == null
          || !ids.add(node.id())
          || (previous >= 0 && previous != node.fromOrdinal())
          || node.endOrdinal() > publication.segmentCount()) {
        throw ModelValues.invalid();
      }
      previous = node.endOrdinal();
    }
    nodes = List.copyOf(nodes);
  }

  @Override
  public String toString() {
    return "SynopsisReductionInput[redacted]";
  }
}
