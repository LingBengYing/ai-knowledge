package com.evidence.rag.model.domain;

public record CleanupBatchItem(
    String documentId, String status, DocumentCleanupState cleanup, String errorCode) {
  public CleanupBatchItem {
    ModelValues.identifier(documentId, 100);
    boolean valid =
        switch (status == null ? "" : status) {
          case "accepted" ->
              cleanup != null && documentId.equals(cleanup.documentId()) && errorCode == null;
          case "busy" -> cleanup == null && "document_busy".equals(errorCode);
          case "not_found" -> cleanup == null && "not_found".equals(errorCode);
          default -> false;
        };
    if (!valid) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "CleanupBatchItem[redacted]";
  }
}
