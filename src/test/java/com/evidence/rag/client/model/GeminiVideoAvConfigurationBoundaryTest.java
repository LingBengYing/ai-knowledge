package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeminiVideoAvConfigurationBoundaryTest {
  private static final Endpoint LOCAL =
      new Endpoint(URI.create("http://127.0.0.1:1"), "explicit-video-model", "synthetic-key");

  @ParameterizedTest
  @ValueSource(strings = {"default", "UNKNOWN", "", "revision has whitespace"})
  void analysisEmbeddingAndDecoderEachRequireAnExplicitStableRevision(String revision) {
    invalid(
        () ->
            new GeminiVideoAvModels.Configuration(
                LOCAL, revision, Duration.ofSeconds(1), 65536, true));
    invalid(
        () ->
            new GeminiVideoAvEmbeddingModels.Configuration(
                LOCAL, revision, 3, "decoder-v1", Duration.ofSeconds(1), 65536, true));
    invalid(
        () ->
            new GeminiVideoAvEmbeddingModels.Configuration(
                LOCAL, "v1", 3, revision, Duration.ofSeconds(1), 65536, true));
  }

  @Test
  void requestDeadlinesAndResponseCapsHaveTheSameFiniteBoundaryForBothProtocols() {
    for (long timeout : new long[] {9, 120001}) {
      invalid(
          () ->
              new GeminiVideoAvModels.Configuration(
                  LOCAL, "v1", Duration.ofMillis(timeout), 65536, true));
      invalid(
          () ->
              new GeminiVideoAvEmbeddingModels.Configuration(
                  LOCAL, "v1", 3, "decoder-v1", Duration.ofMillis(timeout), 65536, true));
    }
    for (int bytes : new int[] {1023, 4194305}) {
      invalid(
          () ->
              new GeminiVideoAvModels.Configuration(
                  LOCAL, "v1", Duration.ofSeconds(1), bytes, true));
      invalid(
          () ->
              new GeminiVideoAvEmbeddingModels.Configuration(
                  LOCAL, "v1", 3, "decoder-v1", Duration.ofSeconds(1), bytes, true));
    }
    var low = new GeminiVideoAvModels.Configuration(LOCAL, "v1", Duration.ofMillis(10), 1024, true);
    var high =
        new GeminiVideoAvModels.Configuration(
            LOCAL, "v1", Duration.ofMillis(120000), 4194304, true);
    assertEquals(low.revision(), high.revision());
    var lowEmbedding =
        new GeminiVideoAvEmbeddingModels.Configuration(
            LOCAL, "v1", 3, "decoder-v1", Duration.ofMillis(10), 1024, true);
    var highEmbedding =
        new GeminiVideoAvEmbeddingModels.Configuration(
            LOCAL, "v1", 3, "decoder-v1", Duration.ofMillis(120000), 4194304, true);
    assertEquals(lowEmbedding.revision(), highEmbedding.revision());
    assertFalse(low.toString().contains("synthetic-key"));
    assertFalse(lowEmbedding.toString().contains("synthetic-key"));
  }

  @Test
  void smallFixtureVectorsRequireLiteralHttpLoopbackWhileProviderDimensionsStayExplicit() {
    for (int dimensions : new int[] {1, 3073}) {
      invalid(
          () ->
              new GeminiVideoAvEmbeddingModels.Configuration(
                  LOCAL, "v1", dimensions, "decoder-v1", Duration.ofSeconds(1), 65536, true));
    }
    for (String endpoint :
        List.of("http://localhost:1", "http://model.invalid", "https://127.0.0.1:1")) {
      invalid(
          () ->
              new GeminiVideoAvEmbeddingModels.Configuration(
                  new Endpoint(URI.create(endpoint), "explicit", "synthetic-key"),
                  "v1",
                  3,
                  "decoder-v1",
                  Duration.ofSeconds(1),
                  65536,
                  true));
    }
    for (String endpoint : List.of("http://127.0.0.1:1", "http://[::1]:1")) {
      var configuration =
          new GeminiVideoAvEmbeddingModels.Configuration(
              new Endpoint(URI.create(endpoint), "explicit", "synthetic-key"),
              "v1",
              2,
              "decoder-v1",
              Duration.ofSeconds(1),
              65536,
              true);
      try (var client = new GeminiVideoAvEmbeddingModels(configuration)) {
        assertEquals(2, client.dimensions());
        assertEquals("decoder-v1", client.decoderRevision());
        assertEquals(configuration.revision(), client.revision());
      }
    }
    for (int dimension : new int[] {128, 3072}) {
      var configuration =
          new GeminiVideoAvEmbeddingModels.Configuration(
              new Endpoint(URI.create("https://model.invalid/"), "explicit", "synthetic-key"),
              "v1",
              dimension,
              "decoder-v1",
              Duration.ofSeconds(1),
              65536,
              false);
      assertEquals(dimension, configuration.dimensions());
      assertTrue(configuration.revision().startsWith("java-video-av-embedding-v1:"));
    }
  }

  @Test
  void analysisEndpointCannotSmuggleAnAlternateApiPathOrModelPath() {
    for (Endpoint endpoint :
        List.of(
            new Endpoint(URI.create("http://127.0.0.1:1/v1beta"), "explicit", "synthetic-key"),
            new Endpoint(URI.create("http://127.0.0.1:1"), "models/explicit", "synthetic-key"))) {
      invalid(
          () ->
              new GeminiVideoAvModels.Configuration(
                  endpoint, "v1", Duration.ofSeconds(1), 65536, true));
    }
    var configuration =
        new GeminiVideoAvModels.Configuration(
            new Endpoint(URI.create("http://127.0.0.1:1/"), "explicit", "synthetic-key"),
            "v1",
            Duration.ofSeconds(1),
            65536,
            true);
    assertTrue(configuration.revision().startsWith("java-video-av-models-v1:"));
  }

  private static void invalid(Runnable operation) {
    var failure = assertThrows(TextModels.Failure.class, operation::run);
    assertEquals("model_invalid_configuration", failure.code());
    assertFalse(failure.toString().contains("synthetic-key"));
  }
}
