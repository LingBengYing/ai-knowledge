package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WikiCatalogResult(List<Item> items, long total, int offset, int limit) {
  public WikiCatalogResult {
    items = List.copyOf(items);
  }

  public record Item(
      @JsonProperty("document_id") String documentId,
      String filename,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("media_type") String mediaType,
      String kind,
      String state,
      boolean answerable,
      @JsonProperty("source_revision_id") String sourceRevisionId,
      @JsonProperty("source_sha256") String sourceSha256,
      String excerpt,
      @JsonProperty("match_count") long matchCount) {}
}
