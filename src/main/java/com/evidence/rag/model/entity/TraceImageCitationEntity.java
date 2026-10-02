package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VisualTraceEvidence;

/** Persisted source binding for an image citation; no caption or generated locator is stored. */
public record TraceImageCitationEntity(
    VisualTraceEvidence evidence,
    String publicationId,
    String imageEvidenceId,
    String sourceSha256) {
  public TraceImageCitationEntity {
    ModelValues.identifier(publicationId, 128);
    ModelValues.identifier(imageEvidenceId, 128);
    if (evidence == null || sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "TraceImageCitationEntity[redacted]";
  }
}
