package com.evidence.rag.model.domain;

/** One original typed candidate plus its complete conflict/applicability context. */
public record KnowledgeEvidence(ProductHelpEvidence source, GroundingText context) {
  public KnowledgeEvidence {
    if (source == null
        || context == null
        || !source.physicalId().equals(context.physicalId())
        || !source.text().equals(context.snippet())) {
      throw ModelValues.invalid();
    }
  }

  public Key key() {
    return new Key(source.kind(), source.physicalId());
  }

  public record Key(ProductHelpEvidence.Kind kind, String physicalId) {
    public Key {
      if (kind == null) {
        throw ModelValues.invalid();
      }
      ModelValues.identifier(physicalId, 128);
    }
  }

  @Override
  public String toString() {
    return "KnowledgeEvidence[redacted]";
  }
}
