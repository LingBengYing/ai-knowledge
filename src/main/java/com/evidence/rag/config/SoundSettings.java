package com.evidence.rag.config;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import java.time.Duration;

/** Complete independent sound profile; validation performs no provider request. */
public record SoundSettings(
    GeminiSoundModels.Configuration models,
    GeminiSoundEmbeddingModels.Configuration embedding,
    MilvusRestProjection.Settings projection,
    IndexTarget target,
    Duration processingBudget,
    int maxConcurrent) {
  public SoundSettings {
    if (models == null
        || embedding == null
        || projection == null
        || target == null
        || processingBudget == null
        || processingBudget.compareTo(Duration.ofMillis(10)) < 0
        || processingBudget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 2
        || !projection.collection().startsWith("java_sound")
        || embedding.dimensions() != target.dimensions()
        || embedding.dimensions() != projection.dimension()
        || !embedding.revision().equals(target.embeddingIdentity())
        || !embedding.revision().equals(target.modelRevision())
        || !embedding.revision().equals(projection.embeddingIdentity())
        || !projection.identity().equals(target.projectionIdentity())) {
      throw new IllegalArgumentException("Invalid local sound configuration");
    }
  }

  @Override
  public String toString() {
    return "SoundSettings[redacted]";
  }
}
