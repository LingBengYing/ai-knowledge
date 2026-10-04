package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Whole effective receipt set frozen against one saved current base and one task. */
public record ReindexVectorPlan(
    String jobId,
    String workspaceId,
    PublicationVersion basePublication,
    String setSha256,
    List<ImageVectorBinding> images,
    List<AudioVectorBinding> audios) {
  public ReindexVectorPlan {
    ModelValues.identifier(jobId, 128);
    ModelValues.identifier(workspaceId, 800);
    if (basePublication == null || images == null || audios == null) {
      throw ModelValues.invalid();
    }
    var origins = new HashSet<String>();
    var profiles = new HashSet<String>();
    for (var binding : images) {
      if (binding == null
          || !binding.basePublication().equals(basePublication)
          || !origins.add("image:" + binding.origin().id())
          || !profiles.add(
              "image:" + VectorBindingIdentity.targetSha256(binding.origin().target()))) {
        throw ModelValues.invalid();
      }
    }
    for (var binding : audios) {
      if (binding == null
          || !binding.basePublication().equals(basePublication)
          || !origins.add("audio:" + binding.origin().id())
          || !profiles.add(
              "audio:"
                  + VectorBindingIdentity.targetSha256(binding.origin().target())
                  + ":"
                  + binding.origin().decoderRevision())) {
        throw ModelValues.invalid();
      }
    }
    if (!VectorBindingIdentity.setSha256(workspaceId, basePublication, images, audios)
        .equals(setSha256)) {
      throw ModelValues.invalid();
    }
    images = List.copyOf(images);
    audios = List.copyOf(audios);
  }

  public boolean isEmpty() {
    return images.isEmpty() && audios.isEmpty();
  }

  @Override
  public String toString() {
    return "ReindexVectorPlan[redacted]";
  }
}
