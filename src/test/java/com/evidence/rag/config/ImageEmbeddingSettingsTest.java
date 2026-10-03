package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ImageEmbeddingSettingsTest {
  @Test
  void processingBudgetAndCapacityAreExplicitAndBoundedWithoutDispatch() throws Exception {
    try (var fixture = new Fixture()) {
      for (Duration budget : new Duration[] {Duration.ofMillis(9), Duration.ofMillis(120001)}) {
        fixture.rejected(
            () -> fixture.settings(fixture.models, fixture.projection, fixture.target, budget, 2));
      }
      for (int concurrency : new int[] {0, 9}) {
        fixture.rejected(
            () ->
                fixture.settings(
                    fixture.models,
                    fixture.projection,
                    fixture.target,
                    Duration.ofSeconds(30),
                    concurrency));
      }
      var minimum =
          fixture.settings(
              fixture.models, fixture.projection, fixture.target, Duration.ofMillis(10), 1);
      var maximum =
          fixture.settings(
              fixture.models, fixture.projection, fixture.target, Duration.ofMillis(120000), 8);
      assertEquals(Duration.ofMillis(10), minimum.processingBudget());
      assertEquals(1, minimum.maxConcurrent());
      assertEquals(Duration.ofMillis(120000), maximum.processingBudget());
      assertEquals(8, maximum.maxConcurrent());
      assertEquals(
          minimum.target(),
          maximum.target(),
          "Operational bounds do not replace the pinned image profile");
      assertEquals("ImageEmbeddingSettings[redacted]", maximum.toString());
      assertEquals(0, fixture.requests.get());
    }
  }

  @Test
  void differentModelProjectionOrTargetDimensionsCannotBeAssembled() throws Exception {
    try (var fixture = new Fixture()) {
      var wrongProjection =
          fixture.projection(fixture.target.embeddingIdentity(), "java_image_fixture", 3);
      fixture.rejected(() -> fixture.settings(fixture.models, wrongProjection, fixture.target));
      var wrongTarget =
          new IndexTarget(
              fixture.target.embeddingIdentity(),
              fixture.target.projectionIdentity(),
              fixture.target.modelRevision(),
              3);
      fixture.rejected(() -> fixture.settings(fixture.models, fixture.projection, wrongTarget));
    }
  }

  @Test
  void projectionEmbeddingAndPhysicalCollectionMustMatchThePublishedTarget() throws Exception {
    try (var fixture = new Fixture()) {
      var anotherEmbedding =
          fixture.projection("another-pinned-image-profile", "java_image_fixture", 2);
      fixture.rejected(() -> fixture.settings(fixture.models, anotherEmbedding, fixture.target));
      var anotherCollection =
          fixture.projection(fixture.target.embeddingIdentity(), "java_image_replaced", 2);
      fixture.rejected(() -> fixture.settings(fixture.models, anotherCollection, fixture.target));
    }
  }

  @Test
  void selfConsistentOldProjectionAndTargetCannotHideModelProfileDrift() throws Exception {
    try (var fixture = new Fixture()) {
      var changedModel = fixture.models("image-v2");
      fixture.rejected(() -> fixture.settings(changedModel, fixture.projection, fixture.target));
      var wrongModelRevision =
          new IndexTarget(
              fixture.target.embeddingIdentity(),
              fixture.target.projectionIdentity(),
              "another-pinned-model-revision",
              2);
      fixture.rejected(
          () -> fixture.settings(fixture.models, fixture.projection, wrongModelRevision));
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final AtomicInteger requests = new AtomicInteger();
    private final HttpServer server;
    private final URI endpoint;
    private final SiliconFlowImageEmbeddingModels.Configuration models;
    private final MilvusRestProjection.Settings projection;
    private final IndexTarget target;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              requests.incrementAndGet();
              exchange.sendResponseHeaders(503, -1);
            }
          });
      server.start();
      endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      models = models("image-v1");
      try (var client = new SiliconFlowImageEmbeddingModels(models)) {
        projection = projection(client.revision(), "java_image_fixture", 2);
        target = new IndexTarget(client.revision(), projection.identity(), client.revision(), 2);
      }
    }

    SiliconFlowImageEmbeddingModels.Configuration models(String revision) {
      return new SiliconFlowImageEmbeddingModels.Configuration(
          new Endpoint(endpoint.resolve("/v1"), "synthetic-model", "private-synthetic-key"),
          revision,
          2,
          Duration.ofSeconds(3),
          65536,
          true);
    }

    MilvusRestProjection.Settings projection(String identity, String collection, int dimensions) {
      return new MilvusRestProjection.Settings(
          endpoint,
          "private-synthetic-key",
          "default",
          collection,
          "org-main",
          identity,
          dimensions,
          Duration.ofSeconds(3),
          65536,
          true);
    }

    ImageEmbeddingSettings settings(
        SiliconFlowImageEmbeddingModels.Configuration currentModels,
        MilvusRestProjection.Settings currentProjection,
        IndexTarget currentTarget) {
      return settings(currentModels, currentProjection, currentTarget, Duration.ofSeconds(30), 2);
    }

    ImageEmbeddingSettings settings(
        SiliconFlowImageEmbeddingModels.Configuration currentModels,
        MilvusRestProjection.Settings currentProjection,
        IndexTarget currentTarget,
        Duration budget,
        int concurrency) {
      return new ImageEmbeddingSettings(
          currentModels, currentProjection, currentTarget, budget, concurrency);
    }

    void rejected(Runnable action) {
      var failure = assertThrows(IllegalArgumentException.class, action::run);
      assertEquals("Invalid local image embedding configuration", failure.getMessage());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("private-synthetic-key"));
      assertFalse(failure.toString().contains(endpoint.toString()));
      assertEquals(
          0, requests.get(), "Invalid configuration must never probe a model or projection");
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
