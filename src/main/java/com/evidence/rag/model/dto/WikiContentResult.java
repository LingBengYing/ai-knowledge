package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Public page content without projection targets, credentials or original binary bytes. */
public record WikiContentResult(String title, String kind, List<Section> sections) {
  public WikiContentResult {
    sections = List.copyOf(sections);
  }

  public record Section(String id, String heading, String body, List<Source> sources) {
    public Section {
      sources = List.copyOf(sources);
    }

    @Override
    public String toString() {
      return "WikiContentResult.Section[redacted]";
    }
  }

  public record Source(
      String id,
      @JsonProperty("document_id") String documentId,
      @JsonProperty("publication_id") String publicationId,
      @JsonProperty("source_revision_id") String sourceRevisionId,
      @JsonProperty("source_sha256") String sourceSha256,
      @JsonProperty("evidence_id") String evidenceId,
      @JsonProperty("evidence_sha256") String evidenceSha256,
      String kind,
      @JsonProperty("start_us") Long startUs,
      @JsonProperty("end_us") Long endUs,
      boolean current) {}

  @Override
  public String toString() {
    return "WikiContentResult[redacted]";
  }
}
