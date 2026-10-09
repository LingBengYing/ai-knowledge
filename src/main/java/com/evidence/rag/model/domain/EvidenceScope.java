package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete frozen authorized publication set, including documents not returned as candidates. */
public record EvidenceScope(
    Actor actor, DocumentSelection selection, List<PublicationVersion> publications) {
  public EvidenceScope {
    if (actor == null || selection == null || publications == null) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    for (PublicationVersion publication : publications) {
      if (publication == null || !ids.add(publication.documentId())) {
        throw ModelValues.invalid();
      }
    }
    if (!selection.all() && !ids.equals(new HashSet<>(selection.documentIds()))) {
      throw ModelValues.invalid();
    }
    publications = List.copyOf(publications);
  }

  @Override
  public String toString() {
    return "EvidenceScope[redacted]";
  }
}
