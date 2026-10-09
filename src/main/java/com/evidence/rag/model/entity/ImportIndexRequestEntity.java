package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.Actor;

public record ImportIndexRequestEntity(
    String revisionId,
    String documentId,
    Actor actor,
    String pipeline,
    String replacementId,
    String baseRevisionId,
    String parseState) {
  @Override
  public String toString() {
    return "ImportIndexRequestEntity[redacted]";
  }
}
