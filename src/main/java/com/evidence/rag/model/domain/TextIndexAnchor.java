package com.evidence.rag.model.domain;

import java.net.URI;
import java.util.Set;

/** Original indexing basis; all fields are non-secret and independent of current answer roles. */
public record TextIndexAnchor(
    long originatingVersion,
    String providerBaseUrl,
    String embeddingModel,
    String embeddingRevision,
    int dimensions,
    String rerankModel,
    String generationModel,
    IndexTarget target,
    String rerankProviderBaseUrl,
    String generationProviderBaseUrl,
    String projectionCollection) {
  public TextIndexAnchor(
      long originatingVersion,
      String providerBaseUrl,
      String embeddingModel,
      String embeddingRevision,
      int dimensions,
      String rerankModel,
      String generationModel,
      IndexTarget target) {
    this(
        originatingVersion,
        providerBaseUrl,
        embeddingModel,
        embeddingRevision,
        dimensions,
        rerankModel,
        generationModel,
        target,
        providerBaseUrl,
        providerBaseUrl,
        null);
  }

  public TextIndexAnchor(
      long originatingVersion,
      String providerBaseUrl,
      String embeddingModel,
      String embeddingRevision,
      int dimensions,
      String rerankModel,
      String generationModel,
      IndexTarget target,
      String rerankProviderBaseUrl,
      String generationProviderBaseUrl) {
    this(
        originatingVersion,
        providerBaseUrl,
        embeddingModel,
        embeddingRevision,
        dimensions,
        rerankModel,
        generationModel,
        target,
        rerankProviderBaseUrl,
        generationProviderBaseUrl,
        null);
  }

  public TextIndexAnchor {
    if (originatingVersion < 1
        || originatingVersion > ModelConfigurationState.MAX_VERSION
        || target == null
        || dimensions != target.dimensions()) {
      throw ModelValues.invalid();
    }
    if (projectionCollection != null
        && !projectionCollection.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
      throw ModelValues.invalid();
    }
    validateEndpoint(providerBaseUrl);
    validateEndpoint(rerankProviderBaseUrl);
    validateEndpoint(generationProviderBaseUrl);
    TextModelConfiguration.validateModel(embeddingModel, "embedding.model");
    TextModelConfiguration.validateRevision(embeddingRevision);
    TextModelConfiguration.validateDimensions(dimensions);
    TextModelConfiguration.validateModel(rerankModel, "rerank.model");
    TextModelConfiguration.validateModel(generationModel, "generation.model");
  }

  public boolean matchesEmbedding(TextModelConfiguration configuration) {
    return configuration != null
        && embeddingModel.equals(configuration.embedding().model())
        && embeddingRevision.equals(configuration.embedding().revision())
        && dimensions == configuration.embedding().dimensions();
  }

  public boolean matchesOriginalRoles(TextModelConfiguration configuration) {
    return matchesEmbedding(configuration)
        && rerankModel.equals(configuration.rerank().model())
        && generationModel.equals(configuration.generation().model())
        && "siliconflow".equals(configuration.embedding().provider())
        && "siliconflow".equals(configuration.rerank().provider())
        && (isDeepSeekEndpoint(generationProviderBaseUrl) ? "deepseek" : "siliconflow")
            .equals(configuration.generation().provider());
  }

  /** Official server-selected alternatives; do not normalize an already anchored URI. */
  public static boolean isDeepSeekEndpoint(String value) {
    return value != null
        && Set.of(
                "https://api.deepseek.com",
                "https://api.deepseek.com/",
                "https://api.deepseek.com/v1",
                "https://api.deepseek.com/v1/")
            .contains(value);
  }

  private static void validateEndpoint(String value) {
    URI provider;
    try {
      provider = URI.create(value);
    } catch (RuntimeException invalid) {
      throw ModelValues.invalid();
    }
    if (!("https".equals(provider.getScheme()) || "http".equals(provider.getScheme()))
        || provider.getHost() == null
        || provider.getRawUserInfo() != null
        || provider.getRawQuery() != null
        || provider.getRawFragment() != null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "TextIndexAnchor[redacted]";
  }
}
