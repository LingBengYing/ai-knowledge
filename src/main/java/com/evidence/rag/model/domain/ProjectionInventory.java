package com.evidence.rag.model.domain;

import java.util.List;

public record ProjectionInventory(boolean known, List<ProjectionAttempt> attempts) {
  public ProjectionInventory {
    attempts = List.copyOf(attempts);
  }

  @Override
  public String toString() {
    return "ProjectionInventory[redacted]";
  }
}
