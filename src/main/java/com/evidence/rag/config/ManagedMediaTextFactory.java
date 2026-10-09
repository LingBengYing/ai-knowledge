package com.evidence.rag.config;

import com.evidence.rag.client.model.AudioEmbeddingModels;
import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.ImageEmbeddingModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.QueryPreparationService;
import com.evidence.rag.service.VideoAnswerProposalService;
import com.evidence.rag.service.VisualAnswerService;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Uses actual optional media resources; it never invents an old text client or index target. */
@Component
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class ManagedMediaTextFactory {
  public record Bundle(
      QueryAttachmentService queries,
      VideoAnswerProposalService video,
      VisualAnswerService visual) {}

  private final EvidenceService evidence;
  private final AnswersSettings limits;
  private final ObjectProvider<VisionModels> vision;
  private final ObjectProvider<VideoConfiguration.VideoResources> videos;
  private final ObjectProvider<QueryPreparationService> preparation;
  private final ObjectProvider<QueryRankingModels> ranking;
  private final ObjectProvider<ImageEmbeddingModels> imageModels;
  private final ObjectProvider<RetrievalProjection> imageProjection;
  private final ObjectProvider<IndexTarget> imageTarget;
  private final ObjectProvider<AudioEmbeddingModels> audioModels;
  private final ObjectProvider<RetrievalProjection> audioProjection;
  private final ObjectProvider<IndexTarget> audioTarget;

  public ManagedMediaTextFactory(
      EvidenceService evidence,
      AnswersSettings limits,
      ObjectProvider<VisionModels> vision,
      ObjectProvider<VideoConfiguration.VideoResources> videos,
      ObjectProvider<QueryPreparationService> preparation,
      ObjectProvider<QueryRankingModels> ranking,
      ObjectProvider<ImageEmbeddingModels> imageModels,
      @Qualifier("imageVectorProjection") ObjectProvider<RetrievalProjection> imageProjection,
      @Qualifier("imageVectorTarget") ObjectProvider<IndexTarget> imageTarget,
      ObjectProvider<AudioEmbeddingModels> audioModels,
      @Qualifier("audioVectorProjection") ObjectProvider<RetrievalProjection> audioProjection,
      @Qualifier("audioVectorTarget") ObjectProvider<IndexTarget> audioTarget) {
    this.evidence = Objects.requireNonNull(evidence);
    this.limits = Objects.requireNonNull(limits);
    this.vision = vision;
    this.videos = videos;
    this.preparation = preparation;
    this.ranking = ranking;
    this.imageModels = imageModels;
    this.imageProjection = imageProjection;
    this.imageTarget = imageTarget;
    this.audioModels = audioModels;
    this.audioProjection = audioProjection;
    this.audioTarget = audioTarget;
  }

  public Bundle build(TextModels text, RetrievalProjection projection, TextIndexAnchor anchor) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(projection);
    Objects.requireNonNull(anchor);
    var prepared = preparation.getIfAvailable();
    var ranks = ranking.getIfAvailable();
    var queries =
        prepared == null || ranks == null
            ? null
            : QueryAttachmentService.managed(
                prepared,
                ranks,
                text,
                projection,
                anchor.target(),
                imageModels.getIfAvailable(),
                imageProjection.getIfAvailable(),
                imageTarget.getIfAvailable(),
                audioModels.getIfAvailable(),
                audioProjection.getIfAvailable(),
                audioTarget.getIfAvailable(),
                anchor);
    var videoResources = videos.getIfAvailable();
    VideoAnswerProposalService video = null;
    if (videoResources != null && videoResources.vision != null && limits.enabled()) {
      if (!(text instanceof FactTextModels facts)) {
        throw new IllegalArgumentException("Video answers require fact-scoped text models");
      }
      video =
          VideoAnswerProposalService.managed(
              evidence,
              text,
              facts,
              videoResources.vision,
              projection,
              anchor.target(),
              Duration.ofMillis(limits.timeoutMs()),
              anchor);
    }
    var images = vision.getIfAvailable();
    var visual =
        images == null || !limits.enabled()
            ? null
            : VisualAnswerService.managed(
                evidence,
                text,
                images,
                projection,
                anchor.target(),
                Duration.ofMillis(limits.timeoutMs()),
                limits.maxConcurrent(),
                queries,
                anchor);
    return new Bundle(queries, video, visual);
  }

  public boolean visualPresent() {
    return vision.getIfAvailable() != null;
  }

  public boolean videoPresent() {
    return videos.getIfAvailable() != null;
  }

  public boolean attachmentsPresent() {
    return preparation.getIfAvailable() != null && ranking.getIfAvailable() != null;
  }
}
