package com.evidence.rag.model.domain;

/** Authority-owned original-image identity and recall metadata, without a text locator. */
public record PublishedImageEvidence(
    PublicationVersion publication,
    String physicalSegmentId,
    String entrySha256,
    ImageEvidence image,
    String filename,
    String mediaType) {
  public PublishedImageEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(filename, 255);
    if (publication == null
        || image == null
        || !image.revisionId().equals(publication.sourceRevisionId())
        || !new VisualIngestionOptions(image.descriptionRevision())
            .parserRevision()
            .equals(publication.parserRevision())
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || !("image/png".equals(mediaType) || "image/jpeg".equals(mediaType))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "PublishedImageEvidence[redacted]";
  }
}
