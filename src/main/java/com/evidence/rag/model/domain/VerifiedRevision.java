package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ProjectionException;

/** Complete projection verification receipt, bound to the immutable revision manifest. */
public record VerifiedRevision(String projectionIdentity, String manifestSha256, int segmentCount) {
  public VerifiedRevision {
    if (projectionIdentity == null
        || !projectionIdentity.matches("[a-f0-9]{64}")
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")
        || segmentCount < 1
        || segmentCount > 4096) {
      throw new ProjectionException("projection_invalid_input");
    }
  }

  @Override
  public String toString() {
    return "VerifiedRevision[redacted]";
  }
}
