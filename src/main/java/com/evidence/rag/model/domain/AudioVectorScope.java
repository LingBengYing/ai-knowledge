package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete current scope and all original speech vectors with an explicit current-base mapping. */
public record AudioVectorScope(
    EvidenceScope base,
    IndexTarget target,
    String decoderRevision,
    List<AudioVectorPublication> publications,
    List<AudioVectorBinding> bindings) {
  public AudioVectorScope(
      EvidenceScope base,
      IndexTarget target,
      String decoderRevision,
      List<AudioVectorPublication> publications) {
    this(
        base,
        target,
        decoderRevision,
        publications,
        publications == null
            ? null
            : publications.stream().map(VectorBindingIdentity::directAudio).toList());
  }

  public AudioVectorScope {
    if (base == null
        || target == null
        || publications == null
        || bindings == null
        || publications.size() > 128
        || publications.size() != bindings.size()) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(decoderRevision, 200);
    var documents = new HashSet<String>();
    var vectors = new HashSet<String>();
    var bases = new HashSet<String>();
    for (int index = 0; index < bindings.size(); index++) {
      var binding = bindings.get(index);
      var publication = publications.get(index);
      if (publication == null
          || binding == null
          || !publication.equals(binding.origin())
          || !publication.target().equals(target)
          || !publication.decoderRevision().equals(decoderRevision)
          || !base.publications().contains(binding.basePublication())
          || !documents.add(binding.basePublication().documentId())) {
        throw ModelValues.invalid();
      }
      for (int entry = 0; entry < publication.entries().size(); entry++) {
        if (!vectors.add(publication.entries().get(entry).vectorPhysicalSegmentId())
            || !bases.add(binding.currentBasePhysicalSegmentIds().get(entry))) {
          throw ModelValues.invalid();
        }
      }
    }
    publications = List.copyOf(publications);
    bindings = List.copyOf(bindings);
  }

  @Override
  public String toString() {
    return "AudioVectorScope[redacted]";
  }
}
