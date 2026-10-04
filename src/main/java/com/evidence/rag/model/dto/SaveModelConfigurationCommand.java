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

  public record EmbeddingInput(
      String model, int dimensions, String revision, String apiKey, String provider) {
    public EmbeddingInput(String model, int dimensions, String revision, String apiKey) {
      this(model, dimensions, revision, apiKey, null);
    }

    public EmbeddingInput {
      TextModelConfiguration.validateModel(model, "embedding.model");
      TextModelConfiguration.validateDimensions(dimensions);
      TextModelConfiguration.validateRevision(revision);
      apiKey = blankKey(apiKey);
      if (apiKey != null) {
        TextModelConfiguration.validateKey(apiKey, "embedding.api_key");
      }
      if (provider != null) {
        TextModelConfiguration.validateProvider(provider, "embedding.provider", false);
      }
    }

    @Override
    public String toString() {
      return "EmbeddingInput[redacted]";
    }
  }

  public record RoleInput(String model, String apiKey, String provider) {
    public RoleInput(String model, String apiKey) {
      this(model, apiKey, null);
    }

    public RoleInput {
      apiKey = blankKey(apiKey);
    }

    @Override
    public String toString() {
      return "RoleInput[redacted]";
    }
  }

  public TextModelConfiguration resolve(TextModelConfiguration previous) {
    String embeddingProvider =
        provider(embedding.provider(), previous == null ? null : previous.embedding().provider());
    String rerankProvider =
        provider(rerank.provider(), previous == null ? null : previous.rerank().provider());
    String generationProvider =
        provider(generation.provider(), previous == null ? null : previous.generation().provider());
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            embedding.model(),
            key(
                embedding.apiKey(),
                previous == null ? null : previous.embedding().apiKey(),
                embeddingProvider,
                previous == null ? null : previous.embedding().provider(),
                "embedding"),
            embedding.dimensions(),
            embedding.revision(),
            embeddingProvider),
        new TextModelConfiguration.Role(
            rerank.model(),
            key(
                rerank.apiKey(),
                previous == null ? null : previous.rerank().apiKey(),
                rerankProvider,
                previous == null ? null : previous.rerank().provider(),
                "rerank"),
            rerankProvider),
        new TextModelConfiguration.Role(
            generation.model(),
            key(
                generation.apiKey(),
                previous == null ? null : previous.generation().apiKey(),
                generationProvider,
                previous == null ? null : previous.generation().provider(),
                "generation"),
            generationProvider));
  }

  private static void validateRole(RoleInput role, String name) {
    TextModelConfiguration.validateModel(role.model(), name + ".model");
    if (role.provider() != null) {
      TextModelConfiguration.validateProvider(
          role.provider(), name + ".provider", "generation".equals(name));
    }
    if (role.apiKey() != null) {
      TextModelConfiguration.validateKey(role.apiKey(), name + ".api_key");
    }
  }

  private static String provider(String next, String previous) {
    return next != null ? next : previous == null ? "siliconflow" : previous;
  }

  private static String blankKey(String value) {
    return value != null && value.isBlank() ? null : value;
  }

  private static String key(
      String next, String previous, String provider, String previousProvider, String role) {
    if (next == null && !provider.equals(previousProvider)) {
      TextModelConfiguration.validateKey(null, role + ".api_key");
    }
    String value = next == null ? previous : next;
    TextModelConfiguration.validateKey(value, role + ".api_key");
    return value;
  }

  @Override
  public String toString() {
    return "SaveModelConfigurationCommand[redacted]";
  }
}
