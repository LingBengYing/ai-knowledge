package com.evidence.rag.model.domain;

import java.util.List;

public record ManagedBackupInventory(boolean known, List<ManagedBackupFile> files) {
  public ManagedBackupInventory {
    files = List.copyOf(files);
  }

  @Override
  public String toString() {
    return "ManagedBackupInventory[redacted]";
  }
}
