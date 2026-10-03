package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete authority scope plus the exact independent image-vector receipts used by a query. */
public record ImageVectorScope(
    EvidenceScope authority, IndexTarget imageTarget, List<ImageVectorPublication> publications) {
  public ImageVectorScope {
    if (authority == null
        || imageTarget == null
        || publications == null
        || publications.size() > 128) {
      throw ModelValues.invalid();
    }
    var documents = new HashSet<String>();
    var vectors = new HashSet<String>();
    var bases = new HashSet<String>();
    for (var publication : publications) {
      if (publication == null
          || !publication.target().equals(imageTarget)
          || !authority.publications().contains(publication.basePublication())
          || !documents.add(publication.basePublication().documentId())
          || !vectors.add(publication.vectorPhysicalSegmentId())
          || !bases.add(publication.basePhysicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    publications = List.copyOf(publications);
  }

  @Override
  public String toString() {
    return "ImageVectorScope[redacted]";
  }
}
