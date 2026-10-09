package com.evidence.rag.model.domain;

import java.util.List;

/** Original factual quotes and the original identity quotes required whenever they are cited. */
public record KnowledgeGroundingResult(GroundingResult proof, List<Dependency> dependencies) {
  public KnowledgeGroundingResult {
    dependencies = List.copyOf(dependencies);
    if (proof == null
        || (!proof.supported() && !dependencies.isEmpty())
        || dependencies.stream()
            .anyMatch(
                dependency ->
                    !proof.quotes().contains(dependency.dependent())
                        || !proof.quotes().contains(dependency.required()))) {
      throw ModelValues.invalid();
    }
  }

  public record Dependency(GroundedQuote dependent, GroundedQuote required) {
    public Dependency {
      if (dependent == null || required == null || dependent.equals(required)) {
        throw ModelValues.invalid();
      }
    }
  }
}
