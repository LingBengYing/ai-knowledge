package com.evidence.rag.model.domain;

/** A real complete origin-generation check, never a claim that a new vector was generated. */
public record ReindexVectorVerification(
    String route, String originReceiptId, IndexTarget target, VerifiedRevision verified) {
  public ReindexVectorVerification {
    ModelValues.identifier(originReceiptId, 128);
    if ((!"image".equals(route) && !"audio".equals(route))
        || target == null
        || verified == null
        || !target.projectionIdentity().equals(verified.projectionIdentity())) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ReindexVectorVerification[redacted]";
  }
}
