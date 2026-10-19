package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Manifest entries appended to an open batch. Ordinals are explicit and consecutive: an entry at an
 * ordinal already registered must repeat it exactly (a replay), new entries continue right after
 * the last registered one.
 */
public record DocumentBatchAppendCommand(List<Entry> files) {
  public DocumentBatchAppendCommand {
    files =
        files == null
            ? null
            : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(files));
  }

  public record Entry(
      Integer ordinal,
      String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("source_sha256") String sourceSha256,
      @JsonProperty("relative_path") String relativePath) {}
}
