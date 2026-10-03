package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.TextModelConfiguration;
import java.util.Objects;

public record SaveModelConfigurationCommand(
    long baseVersion, EmbeddingInput embedding, RoleInput rerank, RoleInput generation) {
  public SaveModelConfigurationCommand {
    TextModelConfiguration.validateBaseVersion(baseVersion);
    Objects.requireNonNull(embedding);
    Objects.requireNonNull(rerank);
    Objects.requireNonNull(generation);
    validateRole(rerank, "rerank");
    validateRole(generation, "generation");
  }

  public record EmbeddingInput(String model, int dimensions, String revision, String apiKey) {
    public EmbeddingInput {
      TextModelConfiguration.validateModel(model, "embedding.model");
      TextModelConfiguration.validateDimensions(dimensions);
      TextModelConfiguration.validateRevision(revision);
      if (apiKey != null) {
        TextModelConfiguration.validateKey(apiKey, "embedding.api_key");
      }
    }

    @Override
    public String toString() {
      return "EmbeddingInput[redacted]";
    }
  }

  public record RoleInput(String model, String apiKey) {
    @Override
    public String toString() {
      return "RoleInput[redacted]";
    }
  }

  public TextModelConfiguration resolve(TextModelConfiguration previous) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            embedding.model(),
            key(
                embedding.apiKey(),
                previous == null ? null : previous.embedding().apiKey(),
                "embedding"),
            embedding.dimensions(),
            embedding.revision()),
        new TextModelConfiguration.Role(
            rerank.model(),
            key(rerank.apiKey(), previous == null ? null : previous.rerank().apiKey(), "rerank")),
        new TextModelConfiguration.Role(
            generation.model(),
            key(
                generation.apiKey(),
                previous == null ? null : previous.generation().apiKey(),
                "generation")));
  }

  private static void validateRole(RoleInput role, String name) {
    TextModelConfiguration.validateModel(role.model(), name + ".model");
    if (role.apiKey() != null) {
      TextModelConfiguration.validateKey(role.apiKey(), name + ".api_key");
    }
  }

  private static String key(String next, String previous, String role) {
    String value = next == null ? previous : next;
    TextModelConfiguration.validateKey(value, role + ".api_key");
    return value;
  }

  @Override
  public String toString() {
    return "SaveModelConfigurationCommand[redacted]";
  }
}
