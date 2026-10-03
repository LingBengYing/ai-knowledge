package com.evidence.rag.model.domain;

/** Current complete source eligibility and optional saved vector publication. */
public record AudioVectorState(
    PublicationVersion basePublication, IndexTarget target, AudioVectorPublication publication) {
  public AudioVectorState {
    if (basePublication == null
        || target == null
        || (publication != null
            && (!publication.basePublication().equals(basePublication)
                || !publication.target().equals(target)))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "AudioVectorState[redacted]";
  }
}
