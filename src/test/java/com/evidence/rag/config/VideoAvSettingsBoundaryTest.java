package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.model.GeminiVideoAvModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VideoAvTargets;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAvSettingsBoundaryTest {
  private static final Endpoint ENDPOINT =
      new Endpoint(URI.create("http://127.0.0.1:1"), "explicit-video-model", "synthetic-key");
  private static final GeminiVideoAvModels.Configuration MODELS =
      new GeminiVideoAvModels.Configuration(
          ENDPOINT, "model-v1", Duration.ofSeconds(1), 65536, true);
  private static final GeminiVideoAvEmbeddingModels.Configuration EMBEDDING =
      new GeminiVideoAvEmbeddingModels.Configuration(
          ENDPOINT, "embedding-v1", 3, "decoder-v1", Duration.ofSeconds(1), 65536, true);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "target-dimensions",
        "projection-dimensions",
        "target-model",
        "projection-model",
        "projection-identity",
        "audio-projection-model",
        "collection-prefix"
      })
  void bothRoutesMustMatchThePinnedEmbeddingSpaceAndTheirOwnReceipt(String drift) {
    var visual =
        projection("java_video_av_visual_test", 3, EMBEDDING.revision(), ENDPOINT.baseUrl());
    var audio = projection("java_video_av_audio_test", 3, EMBEDDING.revision(), ENDPOINT.baseUrl());
    int targetDimensions = drift.equals("target-dimensions") ? 4 : 3;
    String targetModel = drift.equals("target-model") ? "stale-model" : EMBEDDING.revision();
    if (drift.equals("projection-dimensions")) {
      visual = projection(visual.collection(), 4, EMBEDDING.revision(), ENDPOINT.baseUrl());
    } else if (drift.equals("projection-model")) {
      visual = projection(visual.collection(), 3, "stale-model", ENDPOINT.baseUrl());
    } else if (drift.equals("audio-projection-model")) {
      audio = projection(audio.collection(), 3, "stale-model", ENDPOINT.baseUrl());
    } else if (drift.equals("collection-prefix")) {
      visual = projection("java_text_reused", 3, EMBEDDING.revision(), ENDPOINT.baseUrl());
    }
    var targets =
        new VideoAvTargets(
            new IndexTarget(
                EMBEDDING.revision(),
                drift.equals("projection-identity") ? "stale-projection" : visual.identity(),
                targetModel,
                targetDimensions),
            new IndexTarget(EMBEDDING.revision(), audio.identity(), targetModel, targetDimensions));
    var actualVisual = visual;
    var actualAudio = audio;
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VideoAvSettings(
                    MODELS,
                    EMBEDDING,
                    actualVisual,
                    actualAudio,
                    targets,
                    Duration.ofSeconds(30),
                    2));
    assertEquals("Invalid local video audiovisual configuration", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void separateEndpointsCannotHideTheSameCollectionName() {
    var visual = projection("java_video_av_shared", 3, EMBEDDING.revision(), ENDPOINT.baseUrl());
    var audio =
        projection(
            "java_video_av_shared", 3, EMBEDDING.revision(), URI.create("http://127.0.0.1:2"));
    var targets = targets(visual, audio);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VideoAvSettings(
                MODELS, EMBEDDING, visual, audio, targets, Duration.ofSeconds(30), 2));
  }

  @Test
  void boundedProcessingAndAdmissionAcceptEndpointsAndRejectUnboundedWork() {
    var visual =
        projection("java_video_av_visual_test", 3, EMBEDDING.revision(), ENDPOINT.baseUrl());
    var audio = projection("java_video_av_audio_test", 3, EMBEDDING.revision(), ENDPOINT.baseUrl());
    var targets = targets(visual, audio);
    for (int concurrent : new int[] {0, 3}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new VideoAvSettings(
                  MODELS, EMBEDDING, visual, audio, targets, Duration.ofSeconds(30), concurrent));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VideoAvSettings(
                MODELS, EMBEDDING, visual, audio, targets, Duration.ofMillis(120001), 2));
    var minimum =
        new VideoAvSettings(MODELS, EMBEDDING, visual, audio, targets, Duration.ofMillis(10), 1);
    var maximum =
        new VideoAvSettings(
            MODELS, EMBEDDING, visual, audio, targets, Duration.ofMillis(120000), 2);
    assertEquals(minimum.targets(), maximum.targets());
    assertEquals(1, minimum.maxConcurrent());
    assertEquals(2, maximum.maxConcurrent());
  }

  private static VideoAvTargets targets(
      MilvusRestProjection.Settings visual, MilvusRestProjection.Settings audio) {
    return new VideoAvTargets(
        new IndexTarget(EMBEDDING.revision(), visual.identity(), EMBEDDING.revision(), 3),
        new IndexTarget(EMBEDDING.revision(), audio.identity(), EMBEDDING.revision(), 3));
  }

  private static MilvusRestProjection.Settings projection(
      String collection, int dimension, String revision, URI endpoint) {
    return new MilvusRestProjection.Settings(
        endpoint,
        "",
        "default",
        collection,
        "org",
        revision,
        dimension,
        Duration.ofSeconds(1),
        65536,
        true);
  }
}
