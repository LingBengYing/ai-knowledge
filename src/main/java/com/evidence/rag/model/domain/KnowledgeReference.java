package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Server-derived original quote, never a model-generated page or media timestamp. */
public record KnowledgeReference(int citationId, KnowledgeEvidence evidence, int start, int end) {
  public KnowledgeReference {
    if (citationId < 1
        || evidence == null
        || start < evidence.context().startCodePoint()
        || end > evidence.context().endCodePoint()
        || end <= start) {
      throw ModelValues.invalid();
    }
  }

  public String quote() {
    String text = evidence.context().contextText();
    return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
  }

  public String quoteSha256() {
    return ModelValues.sha256(quote().getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "KnowledgeReference[redacted]";
  }
}
