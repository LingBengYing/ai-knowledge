package com.evidence.rag.model.domain;

/** Public terminal identity without question, facts or answer content. */
public record SoundTraceReceipt(String traceId, String status, String reasonCode) {
  public SoundTraceReceipt {
    ModelValues.indexIdentity(traceId);
    if (!("answered".equals(status) && reasonCode == null
        || "abstained".equals(status) && reasonCode != null)) {
      throw ModelValues.invalid();
    }
    if (reasonCode != null) {
      ModelValues.indexIdentity(reasonCode);
    }
  }
}
