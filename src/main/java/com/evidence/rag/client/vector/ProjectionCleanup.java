package com.evidence.rag.client.vector;

import com.evidence.rag.model.domain.ProjectionAttempt;
import java.util.List;
import java.util.Set;

/** Actual remote cleanup seam; logical absence and physical storage are separate proofs. */
@FunctionalInterface
public interface ProjectionCleanup {
  Result clean(List<ProjectionAttempt> attempts);

  record Result(
      String logicalRows, String writeTerminal, String physicalStorage, String errorCode) {
    public Result {
      var statuses = Set.of("completed", "not_applicable", "blocked", "failed");
      if (!statuses.contains(logicalRows)
          || !statuses.contains(writeTerminal)
          || !statuses.contains(physicalStorage)) {
        throw new IllegalArgumentException("Invalid projection cleanup result");
      }
    }
  }
}
