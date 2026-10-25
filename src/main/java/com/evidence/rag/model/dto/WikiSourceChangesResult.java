package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Bounded original-text previews describe a source change; they are not a full textual diff. */
public record WikiSourceChangesResult(
    @JsonProperty("page_id") String pageId,
    long version,
    String title,
    @JsonProperty("can_refresh") boolean canRefresh,
    List<Source> sources) {
  public WikiSourceChangesResult {
    sources = List.copyOf(sources);
  }

  public record Source(
      @JsonProperty("document_id") String documentId,
      @JsonProperty("display_name") String displayName,
      String status,
      Version before,
      Version after) {}

  public record Version(
      @JsonProperty("publication_id") String publicationId,
      @JsonProperty("revision_id") String revisionId,
      @JsonProperty("source_sha256") String sourceSha256,
      @JsonProperty("text_characters") long textCharacters,
      @JsonProperty("part_count") long partCount,
      String excerpt,
      boolean truncated) {}

  @Override
  public String toString() {
    return "WikiSourceChangesResult[redacted]";
  }
}
