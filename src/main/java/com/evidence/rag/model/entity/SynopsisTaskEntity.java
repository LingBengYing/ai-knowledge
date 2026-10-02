package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.PublicationVersion;

/** Internal immutable task snapshot; claim hash and creator are never HTTP response fields. */
public record SynopsisTaskEntity(
    String id,
    Actor creator,
    PublicationVersion publication,
    String modelRevision,
    String policyRevision,
    String state,
    String claimHash,
    String inputFingerprint,
    String errorCode,
    String createdAt,
    String updatedAt) {
  @Override
  public String toString() {
    return "SynopsisTaskEntity[redacted]";
  }
}
