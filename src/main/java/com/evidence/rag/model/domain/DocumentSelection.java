package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Explicit empty selection is distinct from the full authorized library. */
public record DocumentSelection(boolean all, List<String> documentIds) {
  public DocumentSelection {
    if (documentIds == null || (all && !documentIds.isEmpty())) {
      throw ModelValues.invalid();
    }
    var unique = new HashSet<String>();
    for (String id : documentIds) {
      ModelValues.identifier(id, 100);
      if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") || !unique.add(id)) {
        throw ModelValues.invalid();
      }
    }
    documentIds = List.copyOf(documentIds);
  }

  public static DocumentSelection allDocuments() {
    return new DocumentSelection(true, List.of());
  }

  public static DocumentSelection selected(List<String> documentIds) {
    return new DocumentSelection(false, documentIds);
  }
}
