package com.evidence.rag.model.domain;

public record ManagedBackupFile(String relativePath, String sha256, long sizeBytes) {
  public ManagedBackupFile {
    if (relativePath == null
        || !relativePath.matches("[A-Za-z0-9._-]{1,240}")
        || relativePath.equals(".")
        || relativePath.equals("..")
        || sizeBytes < 0
        || sha256 == null
        || !sha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ManagedBackupFile[redacted]";
  }
}
