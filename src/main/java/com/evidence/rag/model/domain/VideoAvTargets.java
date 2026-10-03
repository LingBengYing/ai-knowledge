package com.evidence.rag.model.domain;

public record VideoAvTargets(IndexTarget visual, IndexTarget audio) {
  public VideoAvTargets {
    if (visual == null
        || audio == null
        || !visual.embeddingIdentity().equals(audio.embeddingIdentity())
        || !visual.modelRevision().equals(audio.modelRevision())
        || visual.dimensions() != audio.dimensions()
        || visual.projectionIdentity().equals(audio.projectionIdentity())) {
      throw ModelValues.invalid();
    }
  }

  public IndexTarget target(VideoAvRoute route) {
    return route == VideoAvRoute.VISUAL ? visual : audio;
  }
}
