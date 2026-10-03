package com.evidence.rag.model.domain;

/** Current eligibility and optional immutable receipt, with no original content in diagnostics. */
public record ImageVectorState(
    PublicationVersion basePublication, IndexTarget target, ImageVectorPublication publication) {
  public ImageVectorState {
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
    return "ImageVectorState[redacted]";
  }
}
