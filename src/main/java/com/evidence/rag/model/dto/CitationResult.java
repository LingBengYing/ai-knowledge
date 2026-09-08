package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Server-derived public locator; no projection generation, claim, or page body is exposed. */
public record CitationResult(
    int number,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    String filename,
    int page,
    int start,
    int end,
    String quote,
    @JsonProperty("quote_sha256") String quoteSha256,
    @JsonProperty("source_url") String sourceUrl) {
  @Override
  public String toString() {
    return "CitationResult[redacted]";
  }
}
