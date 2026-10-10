package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelConfigurationResult(
    long version,
    @JsonProperty("active_version") Long activeVersion,
    String state,
    @JsonProperty("can_edit") boolean canEdit,
    String provider,
    EmbeddingResult embedding,
    RoleResult rerank,
    RoleResult generation,
    ProjectionResult projection) {
  public record EmbeddingResult(
      String model,
      Integer dimensions,
      String revision,
      @JsonProperty("has_key") boolean hasKey,
      String provider) {
    public EmbeddingResult(String model, Integer dimensions, String revision, boolean hasKey) {
      this(model, dimensions, revision, hasKey, "siliconflow");
    }
  }

  public record RoleResult(
      String model, @JsonProperty("has_key") boolean hasKey, String provider) {}

  public record ProjectionResult(
      boolean configured, Integer dimension, @JsonProperty("can_test") boolean canTest) {}
}
