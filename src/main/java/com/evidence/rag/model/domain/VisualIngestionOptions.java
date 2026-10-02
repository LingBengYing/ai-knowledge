package com.evidence.rag.model.domain;

/** Explicit visual preparation identity; contains neither credentials nor executable behavior. */
public record VisualIngestionOptions(String modelRevision) {
  public VisualIngestionOptions {
    ModelValues.identifier(modelRevision, 128);
  }

  public String parserRevision() {
    return "java-image-visual-v1:" + modelRevision;
  }

  @Override
  public String toString() {
    return "VisualIngestionOptions[redacted]";
  }
}
