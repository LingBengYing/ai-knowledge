package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete authorized sound set, including publications that are never finally cited. */
public record SoundScope(
    Actor actor, DocumentSelection selection, List<SoundPublication> publications) {
  public SoundScope {
    if (actor == null || selection == null || publications == null || publications.size() > 128) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    for (var publication : publications) {
      if (publication == null
          || !publication.workspaceId().equals(actor.workspaceId())
          || !ids.add(publication.documentId())) {
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
    return "SoundScope[redacted]";
  }
}
