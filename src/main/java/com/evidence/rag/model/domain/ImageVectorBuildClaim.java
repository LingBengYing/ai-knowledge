package com.evidence.rag.model.domain;

import java.util.UUID;

/** Frozen original-image build input; authority is revalidated before publication. */
public record ImageVectorBuildClaim(
    Actor actor,
    PublicationVersion basePublication,
    String imageEvidenceId,
    String basePhysicalSegmentId,
    IndexTarget target,
    String vectorGenerationId,
    VisualImage original) {
  public ImageVectorBuildClaim {
    ModelValues.identifier(imageEvidenceId, 128);
    ModelValues.identifier(basePhysicalSegmentId, 128);
    if (actor == null
        || basePublication == null
        || target == null
        || original == null
        || !original.sha256().equals(basePublication.sourceSha256())) {
      throw ModelValues.invalid();
    }
    try {
      if (!UUID.fromString(vectorGenerationId).toString().equals(vectorGenerationId)) {
        throw ModelValues.invalid();
      }
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ImageVectorBuildClaim[redacted]";
  }
}
