package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Version-bound metadata candidates derived from an available synopsis, never answer evidence. */
public record TagSuggestions(
    String synopsisId,
    PublicationVersion publication,
    String inputFingerprint,
    String modelRevision,
    String synopsisPolicyRevision,
    String suggestionFingerprint,
    List<Candidate> candidates) {
  public static final String POLICY_REVISION = "java-synopsis-tags-v1";

  public record Candidate(int ordinal, String tag) {
    public Candidate {
      String label = ModelValues.label(tag, 40);
      if (ordinal < 1
          || ordinal > 8
          || !label.equals(tag)
          || tag.codePoints().anyMatch(c -> c == ',' || c == '，' || c == ';' || c == '；')) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "TagSuggestions.Candidate[redacted]";
    }
  }

  public TagSuggestions {
    ModelValues.identifier(synopsisId, 128);
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(synopsisPolicyRevision, 200);
    if (publication == null
        || inputFingerprint == null
        || !inputFingerprint.matches("[a-f0-9]{64}")
        || suggestionFingerprint == null
        || !suggestionFingerprint.matches("[a-f0-9]{64}")
        || candidates == null
        || candidates.size() > 8) {
      throw ModelValues.invalid();
    }
    var unique = new HashSet<String>();
    for (int i = 0; i < candidates.size(); i++) {
      Candidate candidate = candidates.get(i);
      if (candidate == null || candidate.ordinal() != i + 1 || !unique.add(candidate.tag())) {
        throw ModelValues.invalid();
      }
    }
    candidates = List.copyOf(candidates);
  }

  public String policyRevision() {
    return POLICY_REVISION;
  }

  @Override
  public String toString() {
    return "TagSuggestions[redacted]";
  }
}
