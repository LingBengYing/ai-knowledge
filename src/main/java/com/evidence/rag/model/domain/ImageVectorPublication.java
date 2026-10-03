package com.evidence.rag.model.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Immutable sidecar; the original text publication remains authoritative. */
public record ImageVectorPublication(
    String id,
    PublicationVersion basePublication,
    String imageEvidenceId,
    String basePhysicalSegmentId,
    String vectorGenerationId,
    String vectorPhysicalSegmentId,
    IndexTarget target,
    String entrySha256,
    String manifestSha256,
    String createdAt) {
  public ImageVectorPublication {
    ModelValues.identifier(id, 128);
    ModelValues.identifier(imageEvidenceId, 128);
    ModelValues.identifier(basePhysicalSegmentId, 128);
    ModelValues.identifier(vectorPhysicalSegmentId, 128);
    if (basePublication == null
        || target == null
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    try {
      if (!UUID.fromString(vectorGenerationId).toString().equals(vectorGenerationId)) {
        throw ModelValues.invalid();
      }
      Instant.parse(createdAt);
    } catch (IllegalArgumentException | DateTimeParseException | NullPointerException failure) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ImageVectorPublication[redacted]";
  }
}
