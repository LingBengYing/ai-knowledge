package com.evidence.rag.model.domain;

/** Saved proof plus the same immutable original source; opening it never invokes a model. */
public record SoundSource(
    String answerId, int ordinal, SoundProof proof, DocumentOriginal original) {
  public SoundSource {
    ModelValues.indexIdentity(answerId);
    if (ordinal < 1 || ordinal > 32 || proof == null || original == null) {
      throw ModelValues.invalid();
    }
    var publication = proof.source().publication();
    if (!publication.documentId().equals(original.documentId())
        || !publication.sourceRevisionId().equals(original.revisionId())
        || !publication.sourceSha256().equals(original.sourceSha256())
        || !publication.filename().equals(original.filename())
        || !publication.mediaType().equals(original.mediaType())
        || publication.sizeBytes() != original.sizeBytes()) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "SoundSource[redacted]";
  }
}
