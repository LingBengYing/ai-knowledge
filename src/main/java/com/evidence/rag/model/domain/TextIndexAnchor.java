package com.evidence.rag.model.domain;

import java.net.URI;

/** Original indexing basis; all fields are non-secret and independent of current answer roles. */
public record TextIndexAnchor(
    long originatingVersion,
    String providerBaseUrl,
    String embeddingModel,
    String embeddingRevision,
    int dimensions,
    String rerankModel,
    String generationModel,
    IndexTarget target) {
  public TextIndexAnchor {
    if (originatingVersion < 1
        || originatingVersion > ModelConfigurationState.MAX_VERSION
        || target == null
        || dimensions != target.dimensions()) {
      throw ModelValues.invalid();
    }
    URI provider;
    try {
      provider = URI.create(providerBaseUrl);
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
        && generationModel.equals(configuration.generation().model());
  }

  @Override
  public String toString() {
    return "TextIndexAnchor[redacted]";
  }
}
