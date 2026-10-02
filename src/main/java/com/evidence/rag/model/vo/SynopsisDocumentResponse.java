package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Navigation-only synopsis with explicitly mapped server-owned source links. */
public record SynopsisDocumentResponse(
    @JsonProperty("synopsis_id") String synopsisId,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("publication_id") String publicationId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("input_fingerprint") String inputFingerprint,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("policy_revision") String policyRevision,
    String status,
    List<Entry> entries) {
  public SynopsisDocumentResponse {
    entries = List.copyOf(entries);
  }

  public record Entry(
      int ordinal, String section, String text, TimeRange interval, List<Reference> evidence) {
    public Entry {
      evidence = List.copyOf(evidence);
    }

    @Override
    public String toString() {
      return "SynopsisDocumentResponse.Entry[redacted]";
    }
  }

  public record TimeRange(
      @JsonProperty("start_us") long startUs, @JsonProperty("end_us") long endUs) {}

  public record Reference(
      int ordinal,
      @JsonProperty("evidence_id") String evidenceId,
      String kind,
      String sha256,
      TimeRange time,
      @JsonProperty("source_url") String sourceUrl) {}

  @Override
  public String toString() {
    return "SynopsisDocumentResponse[redacted]";
  }
}
