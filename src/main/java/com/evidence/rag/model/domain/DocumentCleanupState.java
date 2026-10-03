package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public-safe lifecycle state; accepted withdrawal is distinct from verified cleanup. */
public record DocumentCleanupState(
    String documentId,
    String cleanupId,
    String status,
    String cleanupStatus,
    String requestedAt,
    String updatedAt,
    String completedAt,
    String errorCode,
    List<CleanupResource> resources) {
  public DocumentCleanupState {
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(requestedAt, 100);
    ModelValues.identifier(updatedAt, 100);
    if (status == null
        || cleanupStatus == null
        || !Set.of("deleting", "deleted").contains(status)
        || !Set.of("not_requested", "pending", "running", "blocked", "failed", "completed")
            .contains(cleanupStatus)
        || resources == null
        || resources.stream().anyMatch(java.util.Objects::isNull)
        || ("not_requested".equals(cleanupStatus) != (cleanupId == null))
        || ("completed".equals(cleanupStatus) != "deleted".equals(status))
        || ("completed".equals(cleanupStatus) != (completedAt != null))
        || (Set.of("blocked", "failed").contains(cleanupStatus) && errorCode == null)
        || (errorCode != null && !errorCode.matches("[a-z][a-z0-9_]{0,63}"))
        || ("completed".equals(cleanupStatus)
            && (errorCode != null
                || resources.stream()
                    .anyMatch(r -> !Set.of("completed", "not_applicable").contains(r.status()))))) {
      throw ModelValues.invalid();
    }
    if (cleanupId != null) {
      ModelValues.identifier(cleanupId, 100);
      if (resources.size() != CleanupResource.KINDS.size()
          || new HashSet<>(resources.stream().map(CleanupResource::kind).toList()).size()
              != resources.size()) {
        throw ModelValues.invalid();
      }
    } else if (!resources.isEmpty() || errorCode != null) {
      throw ModelValues.invalid();
    }
    resources = List.copyOf(resources);
  }

  @Override
  public String toString() {
    return "DocumentCleanupState[redacted]";
  }
}
