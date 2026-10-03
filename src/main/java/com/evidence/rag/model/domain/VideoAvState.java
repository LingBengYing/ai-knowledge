package com.evidence.rag.model.domain;

public record VideoAvState(
    DocumentOriginal original,
    IndexTarget visualTarget,
    IndexTarget audioTarget,
    VideoAvPublication publication) {
  public VideoAvState {
    if (original == null || !original.documentType().equals("video")) {
      throw ModelValues.invalid();
    }
    new VideoAvTargets(visualTarget, audioTarget);
    if (publication != null
        && (!publication.documentId().equals(original.documentId())
            || !publication.sourceRevisionId().equals(original.revisionId())
            || !publication.sourceSha256().equals(original.sourceSha256())
            || !publication.filename().equals(original.filename())
            || !publication.mediaType().equals(original.mediaType())
            || publication.sizeBytes() != original.sizeBytes()
            || !publication.visualTarget().equals(visualTarget)
            || !publication.audioTarget().equals(audioTarget))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoAvState[redacted]";
  }
}
