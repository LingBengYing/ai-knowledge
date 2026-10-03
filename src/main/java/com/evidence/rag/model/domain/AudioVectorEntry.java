package com.evidence.rag.model.domain;

/** Immutable source-to-vector mapping; no PCM or model coordinates are persisted. */
public record AudioVectorEntry(
    String audioEvidenceId,
    String basePhysicalSegmentId,
    String vectorPhysicalSegmentId,
    int ordinal,
    long startSample,
    long endSample,
    String pcmSha256,
    String entrySha256) {
  public AudioVectorEntry {
    ModelValues.indexIdentity(audioEvidenceId);
    ModelValues.identifier(basePhysicalSegmentId, 128);
    ModelValues.identifier(vectorPhysicalSegmentId, 128);
    if (ordinal < 0
        || ordinal >= 600
        || startSample < 0
        || endSample <= startSample
        || endSample > 9600000
        || endSample - startSample > 480000
        || pcmSha256 == null
        || !pcmSha256.matches("[a-f0-9]{64}")
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "AudioVectorEntry[redacted]";
  }
}
