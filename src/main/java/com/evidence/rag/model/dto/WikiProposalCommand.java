package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit source selection for a derived page proposal, never a query scope override. */
public record WikiProposalCommand(
    String pageId,
    long baseVersion,
    String title,
    String kind,
    List<String> documentIds,
    String generationMethod) {
  public WikiProposalCommand {
    if (pageId != null) {
      ModelValues.identifier(pageId, 128);
    }
    title = ModelValues.label(title, 200);
    if (baseVersion < 0
        || baseVersion >= 9_007_199_254_740_991L
        || (pageId == null ? baseVersion != 0 : baseVersion == 0)
        || kind == null
        || !Set.of("topic", "entity", "procedure", "overview").contains(kind)
        || generationMethod == null
        || !Set.of("extractive", "model").contains(generationMethod)
        || documentIds == null
        || documentIds.isEmpty()
        || documentIds.size() > 50) {
      throw ModelValues.invalid();
    }
    var unique = new HashSet<String>();
    for (String documentId : documentIds) {
      ModelValues.identifier(documentId, 100);
      if (!unique.add(documentId)) {
        throw ModelValues.invalid();
      }
    }
    documentIds = List.copyOf(documentIds);
  }

  @Override
  public String toString() {
    return "WikiProposalCommand[redacted]";
  }
}
