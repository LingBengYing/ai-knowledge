package com.evidence.rag.model.domain;

/** Hash-only preimage of one body column; rowKey is canonical, not executable SQL. */
public record CleanupPayload(
    String tableName, String rowKey, String bodyColumn, long sizeBytes, String sha256) {
  public CleanupPayload {
    if (tableName == null
        || !tableName.matches("[a-z_]{1,80}")
        || bodyColumn == null
        || !bodyColumn.matches("[a-z_]{1,80}")
        || rowKey == null
        || rowKey.isEmpty()
        || rowKey.length() > 1024
        || sizeBytes < 0
        || sha256 == null
        || !sha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "CleanupPayload[redacted]";
  }
}
