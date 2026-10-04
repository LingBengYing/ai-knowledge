package com.evidence.rag.model.domain;

/** Current base identity with its explicit optional immutable original-vector binding. */
public record ImageVectorState(
    PublicationVersion basePublication,
    IndexTarget target,
    ImageVectorPublication publication,
    ImageVectorBinding binding) {
  public ImageVectorState(
      PublicationVersion basePublication, IndexTarget target, ImageVectorPublication publication) {
    this(
        basePublication,
        target,
        publication,
        publication == null ? null : VectorBindingIdentity.directImage(publication));
  }

  public ImageVectorState {
    if (basePublication == null
        || target == null
        || (publication == null
            ? binding != null
            : binding == null
                || !binding.basePublication().equals(basePublication)
                || !binding.origin().equals(publication)
                || !publication.target().equals(target))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ImageVectorState[redacted]";
  }
}
