package com.evidence.rag.model.domain;

public record VideoAvSource(
    String answerId, int ordinal, VideoAvProof proof, DocumentOriginal original) {
  public VideoAvSource {
    ModelValues.indexIdentity(answerId);
    if (ordinal < 1
        || ordinal > 32
        || proof == null
        || original == null
        || !proof.evidence().publication().documentId().equals(original.documentId())
        || !proof.evidence().publication().sourceSha256().equals(original.sourceSha256())) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoAvSource[redacted]";
  }
}
