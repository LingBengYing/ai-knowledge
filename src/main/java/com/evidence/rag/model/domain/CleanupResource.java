package com.evidence.rag.model.domain;

import java.util.List;
import java.util.Set;

/** Safe application-managed cleanup resource state, never a filesystem or provider address. */
public record CleanupResource(String kind, String status) {
  public static final List<String> KINDS =
      List.of(
          "database_payload",
          "database_file",
          "managed_backups",
          "managed_temporaries",
          "remote_inventory",
          "remote_logical_rows",
          "remote_write_terminal",
          "remote_physical_storage",
          "restore_barrier");

  public CleanupResource {
    if (kind == null
        || status == null
        || !KINDS.contains(kind)
        || !Set.of("pending", "running", "completed", "not_applicable", "blocked", "failed")
            .contains(status)) {
      throw ModelValues.invalid();
    }
  }
}
