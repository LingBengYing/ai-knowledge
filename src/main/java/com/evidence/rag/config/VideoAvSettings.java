package com.evidence.rag.config;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.model.GeminiVideoAvModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VideoAvTargets;
import java.time.Duration;

/** The two projections share one embedding space while retaining separate complete receipts. */
public record VideoAvSettings(
    GeminiVideoAvModels.Configuration models,
    GeminiVideoAvEmbeddingModels.Configuration embedding,
    MilvusRestProjection.Settings visualProjection,
    MilvusRestProjection.Settings audioProjection,
    VideoAvTargets targets,
    Duration processingBudget,
    int maxConcurrent) {
  public VideoAvSettings {
    if (models == null
        || embedding == null
        || visualProjection == null
        || audioProjection == null
        || targets == null
        || processingBudget == null
        || processingBudget.compareTo(Duration.ofMillis(10)) < 0
        || processingBudget.compareTo(Duration.ofMillis(120000)) > 0
        || maxConcurrent < 1
        || maxConcurrent > 2
        || visualProjection.collection().equals(audioProjection.collection())
        || !matches(embedding, visualProjection, targets.visual())
        || !matches(embedding, audioProjection, targets.audio())) {
      throw new IllegalArgumentException("Invalid local video audiovisual configuration");
    }
  }

  private static boolean matches(
      GeminiVideoAvEmbeddingModels.Configuration embedding,
      MilvusRestProjection.Settings projection,
      IndexTarget target) {
    return projection.collection().startsWith("java_video_av_")
        && embedding.dimensions() == target.dimensions()
        && embedding.dimensions() == projection.dimension()
        && embedding.revision().equals(target.embeddingIdentity())
        && embedding.revision().equals(target.modelRevision())
        && embedding.revision().equals(projection.embeddingIdentity())
        && projection.identity().equals(target.projectionIdentity());
  }

  @Override
  public String toString() {
    return "VideoAvSettings[redacted]";
  }
}
