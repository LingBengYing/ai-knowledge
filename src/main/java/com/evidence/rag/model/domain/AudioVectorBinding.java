package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete current speech mapping with all original vectors and sample identities unchanged. */
public record AudioVectorBinding(
    PublicationVersion basePublication,
    AudioVectorPublication origin,
    List<String> currentBasePhysicalSegmentIds,
    String inheritedFromPublicationId,
    String bindingSha256, String modelRebuildId) {
  public AudioVectorBinding(PublicationVersion basePublication, AudioVectorPublication origin,
      List<String> currentBasePhysicalSegmentIds, String inheritedFromPublicationId, String bindingSha256) {
    this(basePublication,origin,currentBasePhysicalSegmentIds,inheritedFromPublicationId,bindingSha256,null);
  }

  public AudioVectorBinding {
    if (basePublication == null
        || origin == null
        || currentBasePhysicalSegmentIds == null
        || currentBasePhysicalSegmentIds.size() != origin.entries().size()) {
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
    var unique = new HashSet<String>();
    for (int ordinal = 0; ordinal < origin.entries().size(); ordinal++) {
      var entry = origin.entries().get(ordinal);
      var current = currentBasePhysicalSegmentIds.get(ordinal);
      if (!unique.add(current)
          || !VectorBindingIdentity.physicalSegmentId(
                  basePublication.projectionGenerationId(), entry.audioEvidenceId())
              .equals(current)
          || !VectorBindingIdentity.physicalSegmentId(
                  origin.basePublication().projectionGenerationId(), entry.audioEvidenceId())
              .equals(entry.basePhysicalSegmentId())
          || !VectorBindingIdentity.physicalSegmentId(
                  origin.vectorGenerationId(), entry.audioEvidenceId())
              .equals(entry.vectorPhysicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    if (!VectorBindingIdentity.audioSha256(
            basePublication, origin, currentBasePhysicalSegmentIds, inheritedFromPublicationId, modelRebuildId)
        .equals(bindingSha256)) {
      throw ModelValues.invalid();
    }
    currentBasePhysicalSegmentIds = List.copyOf(currentBasePhysicalSegmentIds);
  }

  @Override
  public String toString() {
    return "AudioVectorBinding[redacted]";
  }
}
