package com.evidence.rag.model.domain;

/**
 * Immutable authority binding; source revision and projection generation are distinct identities.
 */
public record PublicationVersion(
    String documentId,
    String publicationId,
    String sourceRevisionId,
    String projectionGenerationId,
    String sourceSha256,
    String parserRevision,
    IndexTarget target,
    String manifestSha256,
    int segmentCount) {
  public PublicationVersion {
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(publicationId, 128);
    ModelValues.identifier(sourceRevisionId, 128);
    ModelValues.identifier(projectionGenerationId, 36);
    ModelValues.identifier(parserRevision, 160);
    if (target == null
        || sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")
        || segmentCount < 1
        || segmentCount > 4096) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "PublicationVersion[redacted]";
  }
}
