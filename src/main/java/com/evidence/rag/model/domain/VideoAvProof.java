package com.evidence.rag.model.domain;

import java.util.List;

public record VideoAvProof(
    VideoAvEvidence evidence,
    VideoAvMode mode,
    List<VideoAvFact> facts,
    String factsSha256,
    String proofSha256) {
  public VideoAvProof {
    VideoAvProfile.hash(factsSha256);
    VideoAvProfile.hash(proofSha256);
    if (evidence == null
        || mode == null
        || facts == null
        || !VideoAvProofIdentity.factsSha256(facts).equals(factsSha256)) {
      throw ModelValues.invalid();
    }
    facts = List.copyOf(facts);
  }

  @Override
  public String toString() {
    return "VideoAvProof[redacted]";
  }
}
