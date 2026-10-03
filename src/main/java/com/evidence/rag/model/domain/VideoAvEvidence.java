package com.evidence.rag.model.domain;

public record VideoAvEvidence(VideoAvPublication publication, VideoAvPublishedWindow window) {
  public VideoAvEvidence {
    if (publication == null || window == null || !publication.windows().contains(window)) {
      throw ModelValues.invalid();
    }
  }
}
