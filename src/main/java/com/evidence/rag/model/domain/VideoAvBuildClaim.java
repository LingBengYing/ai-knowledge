package com.evidence.rag.model.domain;

import java.util.UUID;

public record VideoAvBuildClaim(
    Actor actor,
    DocumentOriginal original,
    VideoAvTargets targets,
    String generationId,
    String analysisModelRevision,
    String decoderRevision,
    int chunkSeconds,
    VideoAvCompilation compilation,
    String profileFingerprint) {
  public VideoAvBuildClaim {
    if (actor == null
        || original == null
        || targets == null
        || compilation == null
        || !"video".equals(original.documentType())
        || !original.sourceSha256().equals(compilation.sourceSha256())
        || !decoderRevision.equals(compilation.decoderRevision())
        || !VideoAvProfile.fingerprint(
                targets, analysisModelRevision, decoderRevision, chunkSeconds)
            .equals(profileFingerprint)
        || generationId == null
        || !UUID.fromString(generationId).toString().equals(generationId)) {
      throw ModelValues.invalid();
    }
    for (var w : compilation.windows()) {
      if (!w.id().equals(VideoAvProfile.windowId(original.revisionId(), w.ordinal()))
          || w.endTick() - w.startTick() > compilation.epoch().durationLimit(chunkSeconds)) {
        throw ModelValues.invalid();
      }
    }
  }

  @Override
  public String toString() {
    return "VideoAvBuildClaim[redacted]";
  }
}
