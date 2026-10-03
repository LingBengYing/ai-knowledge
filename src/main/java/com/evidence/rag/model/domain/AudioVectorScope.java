package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete query authority plus every current audio-vector receipt in that scope. */
public record AudioVectorScope(
    EvidenceScope base,
    IndexTarget target,
    String decoderRevision,
    List<AudioVectorPublication> publications) {
  public AudioVectorScope {
    if (base == null || target == null || publications == null || publications.size() > 128) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(decoderRevision, 200);
    var documents = new HashSet<String>();
    var vectors = new HashSet<String>();
    var bases = new HashSet<String>();
    for (var publication : publications) {
      if (publication == null
          || !publication.target().equals(target)
          || !publication.decoderRevision().equals(decoderRevision)
          || !base.publications().contains(publication.basePublication())
          || !documents.add(publication.basePublication().documentId())) {
        throw ModelValues.invalid();
      }
      for (var entry : publication.entries()) {
        if (!vectors.add(entry.vectorPhysicalSegmentId())
            || !bases.add(entry.basePhysicalSegmentId())) {
          throw ModelValues.invalid();
        }
      }
    }
    publications = List.copyOf(publications);
  }

  @Override
  public String toString() {
    return "AudioVectorScope[redacted]";
  }
}
