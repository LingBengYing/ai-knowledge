package com.evidence.rag.model.domain;

/** Current base identity with its explicit optional immutable original-vector binding. */
public record AudioVectorState(
    PublicationVersion basePublication,
    IndexTarget target,
    AudioVectorPublication publication,
    AudioVectorBinding binding) {
  public AudioVectorState(
      PublicationVersion basePublication, IndexTarget target, AudioVectorPublication publication) {
    this(
        basePublication,
        target,
        publication,
        publication == null ? null : VectorBindingIdentity.directAudio(publication));
  }

  public AudioVectorState {
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
    return "AudioVectorState[redacted]";
  }
}
