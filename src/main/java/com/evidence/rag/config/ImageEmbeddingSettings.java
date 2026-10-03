package com.evidence.rag.config;

import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import java.time.Duration;

/**
 * Explicit image model and independent projection configuration; never initializes remote state.
 */
public record ImageEmbeddingSettings(
    SiliconFlowImageEmbeddingModels.Configuration models,
    MilvusRestProjection.Settings projection,
    IndexTarget target,
    Duration processingBudget,
    int maxConcurrent) {
  public ImageEmbeddingSettings {
    if (models == null
        || projection == null
        || target == null
        || processingBudget == null
        || processingBudget.compareTo(Duration.ofMillis(10)) < 0
        || processingBudget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 8
        || models.dimensions() != projection.dimension()
        || models.dimensions() != target.dimensions()
        || !projection.embeddingIdentity().equals(target.embeddingIdentity())
        || !projection.identity().equals(target.projectionIdentity())) {
      throw new IllegalArgumentException("Invalid local image embedding configuration");
    }
    try (var client = new SiliconFlowImageEmbeddingModels(models)) {
      if (!client.revision().equals(target.embeddingIdentity())
          || !client.revision().equals(target.modelRevision())) {
        throw new IllegalArgumentException("Invalid local image embedding configuration");
      }
    }
  }

  @Override
  public String toString() {
    return "ImageEmbeddingSettings[redacted]";
  }
}
