package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Current navigation metadata suggestions; never answer evidence or model-generated locators. */
public record TagSuggestionResponse(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("publication_id") String publicationId,
    @JsonProperty("revision_id") String revisionId,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("synopsis_id") String synopsisId,
    @JsonProperty("input_fingerprint") String inputFingerprint,
    @JsonProperty("model_revision") String modelRevision,
    @JsonProperty("synopsis_policy_revision") String synopsisPolicyRevision,
    @JsonProperty("policy_revision") String policyRevision,
    @JsonProperty("suggestion_fingerprint") String suggestionFingerprint,
    @JsonProperty("existing_tags") List<String> existingTags,
    @JsonProperty("can_apply") boolean canApply,
    List<Candidate> candidates) {
  public TagSuggestionResponse {
    existingTags = List.copyOf(existingTags);
    candidates = List.copyOf(candidates);
  }

  public record Candidate(int ordinal, String tag) {
    @Override
    public String toString() {
      return "TagSuggestionResponse.Candidate[redacted]";
    }
  }

  @Override
  public String toString() {
    return "TagSuggestionResponse[redacted]";
  }
}
