package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete version-bound original materials. Origin is authenticated by the calling Service. */
public record WikiCompilationInput(
    PublicationVersion publication, List<SynopsisEvidence> evidence) {
  public WikiCompilationInput {
    if (publication == null
        || evidence == null
        || evidence.isEmpty()
        || evidence.size() != publication.segmentCount()) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    for (var item : evidence) {
      if (item == null || !ids.add(item.id())) {
        throw ModelValues.invalid();
      }
    }
    evidence = List.copyOf(evidence);
  }

  @Override
  public String toString() {
    return "WikiCompilationInput[redacted]";
  }
}
