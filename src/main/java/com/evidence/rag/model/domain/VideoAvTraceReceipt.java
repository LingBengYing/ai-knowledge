package com.evidence.rag.model.domain;

public record VideoAvTraceReceipt(String traceId, String status, String reasonCode) {
  public VideoAvTraceReceipt {
    ModelValues.indexIdentity(traceId);
    if (!"answered".equals(status) && !"abstained".equals(status)) {
      throw ModelValues.invalid();
    }
  }
}
