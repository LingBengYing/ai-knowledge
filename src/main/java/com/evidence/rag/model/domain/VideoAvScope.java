package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

public record VideoAvScope(
    Actor actor, DocumentSelection selection, List<VideoAvPublication> publications) {
  public VideoAvScope {
    if (actor == null || selection == null || publications == null) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    for (var p : publications) {
      if (p == null || !p.workspaceId().equals(actor.workspaceId()) || !ids.add(p.documentId())) {
        throw ModelValues.invalid();
      }
    }
    if (!selection.all() && !new HashSet<>(selection.documentIds()).equals(ids)) {
      throw ModelValues.invalid();
    }
    publications = List.copyOf(publications);
  }

  @Override
  public String toString() {
    return "VideoAvScope[redacted]";
  }
}
