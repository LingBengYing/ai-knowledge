package com.evidence.rag.model.domain;

/** Current text authority associated with the unchanged originally verified image asset. */
public record ImageVectorBinding(
    PublicationVersion basePublication,
    ImageVectorPublication origin,
    String currentBasePhysicalSegmentId,
    String inheritedFromPublicationId,
    String bindingSha256, String modelRebuildId) {
  public ImageVectorBinding(PublicationVersion basePublication, ImageVectorPublication origin,
      String currentBasePhysicalSegmentId, String inheritedFromPublicationId, String bindingSha256) {
    this(basePublication,origin,currentBasePhysicalSegmentId,inheritedFromPublicationId,bindingSha256,null);
  }

  public ImageVectorBinding {
    if (basePublication == null || origin == null) {
      throw ModelValues.invalid();
    }
    if (modelRebuildId == null) {
      VectorBindingIdentity.requireSameSource(basePublication, origin.basePublication());
    } else {
      ModelValues.identifier(modelRebuildId,128);
      if (inheritedFromPublicationId == null) {
        throw ModelValues.invalid();
      }
      VectorBindingIdentity.requireSameMaterials(basePublication,origin.basePublication());
    }
    VectorBindingIdentity.requireProvenance(
        basePublication, origin.basePublication(), inheritedFromPublicationId);
    if (!VectorBindingIdentity.physicalSegmentId(
                basePublication.projectionGenerationId(), origin.imageEvidenceId())
            .equals(currentBasePhysicalSegmentId)
        || !VectorBindingIdentity.physicalSegmentId(
                origin.basePublication().projectionGenerationId(), origin.imageEvidenceId())
            .equals(origin.basePhysicalSegmentId())
        || !VectorBindingIdentity.physicalSegmentId(
                origin.vectorGenerationId(), origin.imageEvidenceId())
            .equals(origin.vectorPhysicalSegmentId())
        || !VectorBindingIdentity.imageSha256(
                basePublication, origin, currentBasePhysicalSegmentId, inheritedFromPublicationId, modelRebuildId)
            .equals(bindingSha256)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ImageVectorBinding[redacted]";
  }
}
