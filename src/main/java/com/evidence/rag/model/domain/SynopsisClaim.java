package com.evidence.rag.model.domain;

/** One complete bounded input and its private, single-execution processing credential. */
public record SynopsisClaim(
    String taskId,
    Actor creator,
    SynopsisFileInput input,
    String modelRevision,
    String policyRevision,
    String token) {

  public SynopsisClaim {
    ModelValues.identifier(taskId, 128);
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    ModelValues.identifier(token, 128);
    if (creator == null || input == null) {
      throw ModelValues.invalid();
    }
  }

  public PublicationVersion publication() {
    return input.publication();
  }

  @Override
  public String toString() {
    return "SynopsisClaim[redacted]";
  }
}
