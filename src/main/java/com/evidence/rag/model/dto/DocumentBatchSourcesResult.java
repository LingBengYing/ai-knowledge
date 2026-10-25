package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Published sources from one import, ready for an explicitly selected knowledge proposal. */
public record DocumentBatchSourcesResult(
    @JsonProperty("batch_id") String batchId,
    int total,
    int offset,
    int limit,
    List<Item> items,
    List<Folder> folders) {
  public record Item(
      int ordinal,
      @JsonProperty("document_id") String documentId,
      String filename,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("relative_path") String relativePath,
      @JsonProperty("publication_id") String publicationId,
      @JsonProperty("source_revision_id") String sourceRevisionId) {}

  public record Folder(String path, int count) {}
}
