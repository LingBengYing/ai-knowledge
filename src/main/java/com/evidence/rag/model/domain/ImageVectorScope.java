package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete current scope and explicit bindings to unchanged original image assets. */
public record ImageVectorScope(
    EvidenceScope authority,
    IndexTarget imageTarget,
    List<ImageVectorPublication> publications,
    List<ImageVectorBinding> bindings) {
  public ImageVectorScope(
      EvidenceScope authority, IndexTarget imageTarget, List<ImageVectorPublication> publications) {
    this(
        authority,
        imageTarget,
        publications,
        publications == null
            ? null
            : publications.stream().map(VectorBindingIdentity::directImage).toList());
  }

  public ImageVectorScope {
    if (authority == null
        || imageTarget == null
        || publications == null
        || bindings == null
        || publications.size() > 128
        || publications.size() != bindings.size()) {
      throw ModelValues.invalid();
    }
    var documents = new HashSet<String>();
    var vectors = new HashSet<String>();
    var bases = new HashSet<String>();
    for (int index = 0; index < bindings.size(); index++) {
      var binding = bindings.get(index);
      var publication = publications.get(index);
      if (publication == null
          || binding == null
          || !publication.equals(binding.origin())
          || !publication.target().equals(imageTarget)
          || !authority.publications().contains(binding.basePublication())
          || !documents.add(binding.basePublication().documentId())
          || !vectors.add(publication.vectorPhysicalSegmentId())
          || !bases.add(binding.currentBasePhysicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    publications = List.copyOf(publications);
    bindings = List.copyOf(bindings);
  }

  @Override
  public String toString() {
    return "ImageVectorScope[redacted]";
  }
}
