package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoundModelConfigurationBoundaryTest {
  private static final Endpoint REMOTE =
      new Endpoint(URI.create("https://sound.invalid"), "explicit-model", "fixture-key");
  private static final Endpoint LOCAL =
      new Endpoint(URI.create("http://127.0.0.1:1"), "explicit-model", "fixture-key");

  @Test
  void soundModelsRequireExplicitPinnedRevisionAndFiniteOperationalBounds() {
    for (String revision : Arrays.asList(null, "", "latest", "DEFAULT", "unknown", "with space")) {
      invalid(() -> sound(REMOTE, revision, Duration.ofSeconds(5), 65536, false));
    }
    for (Duration deadline : Arrays.asList(null, Duration.ofMillis(9), Duration.ofMillis(120001))) {
      invalid(() -> sound(REMOTE, "pinned-v1", deadline, 65536, false));
    }
    for (int bytes : new int[] {1023, 4194305}) {
      invalid(() -> sound(REMOTE, "pinned-v1", Duration.ofSeconds(5), bytes, false));
    }
    assertEquals(
        sound(REMOTE, "pinned-v1", Duration.ofMillis(10), 1024, false).revision(),
        sound(REMOTE, "pinned-v1", Duration.ofMillis(120000), 4194304, false).revision());
  }

  @Test
  void soundAndEmbeddingEndpointsCannotSmugglePathsModelSegmentsOrPlainRemoteHttp() {
    for (Endpoint endpoint :
        List.of(
            new Endpoint(URI.create("https://sound.invalid/v1beta"), "model", "fixture-key"),
            new Endpoint(URI.create("https://sound.invalid"), "models/name", "fixture-key"),
            new Endpoint(URI.create("http://sound.invalid"), "model", "fixture-key"))) {
      invalid(() -> sound(endpoint, "pinned-v1", Duration.ofSeconds(5), 65536, true));
      invalid(
          () ->
              embedding(
                  endpoint, "pinned-v1", 128, "decoder-v1", Duration.ofSeconds(5), 65536, true));
    }
    var slash = new Endpoint(URI.create("https://sound.invalid/"), "model", "fixture-key");
    sound(slash, "pinned-v1", Duration.ofSeconds(5), 65536, false);
    embedding(slash, "pinned-v1", 128, "decoder-v1", Duration.ofSeconds(5), 65536, false);
  }

  @Test
  void audioEmbeddingRequiresExplicitDecoderAndPinnedModelVersions() {
    for (String revision : Arrays.asList(null, "", "latest", "DEFAULT", "unknown", "with space")) {
      invalid(
          () ->
              embedding(REMOTE, revision, 128, "decoder-v1", Duration.ofSeconds(5), 65536, false));
      invalid(
          () -> embedding(REMOTE, "pinned-v1", 128, revision, Duration.ofSeconds(5), 65536, false));
    }
    var base =
        embedding(REMOTE, "pinned-v1", 128, "decoder-v1", Duration.ofSeconds(5), 65536, false);
    assertNotEquals(
        base.revision(),
        embedding(REMOTE, "pinned-v1", 128, "decoder-v2", Duration.ofSeconds(5), 65536, false)
            .revision());
    assertNotEquals(
        base.revision(),
        embedding(REMOTE, "pinned-v2", 128, "decoder-v1", Duration.ofSeconds(5), 65536, false)
            .revision());
  }

  @Test
  void embeddingDimensionsAndBudgetsHaveClosedBoundsWithLowDimensionsOnlyForLiteralLoopback() {
    for (int dimensions : new int[] {1, 3073}) {
      invalid(
          () ->
              embedding(
                  LOCAL,
                  "pinned-v1",
                  dimensions,
                  "decoder-v1",
                  Duration.ofSeconds(5),
                  65536,
                  true));
    }
    for (Duration deadline : Arrays.asList(null, Duration.ofMillis(9), Duration.ofMillis(120001))) {
      invalid(() -> embedding(LOCAL, "pinned-v1", 3, "decoder-v1", deadline, 65536, true));
    }
    for (int bytes : new int[] {1023, 4194305}) {
      invalid(
          () -> embedding(LOCAL, "pinned-v1", 3, "decoder-v1", Duration.ofSeconds(5), bytes, true));
    }
    invalid(
        () -> embedding(LOCAL, "pinned-v1", 3, "decoder-v1", Duration.ofSeconds(5), 65536, false));
    invalid(
        () ->
            embedding(
                new Endpoint(URI.create("http://localhost:1"), "model", "fixture-key"),
                "pinned-v1",
                3,
                "decoder-v1",
                Duration.ofSeconds(5),
                65536,
                true));
    var ipv6 = new Endpoint(URI.create("http://[::1]:1/"), "model", "fixture-key");
    assertEquals(
        2,
        embedding(ipv6, "pinned-v1", 2, "decoder-v1", Duration.ofMillis(10), 1024, true)
            .dimensions());
    assertEquals(
        3072,
        embedding(
                REMOTE, "pinned-v1", 3072, "decoder-v1", Duration.ofMillis(120000), 4194304, false)
            .dimensions());
  }

  @Test
  void incompleteAdapterConstructionCannotCreateAnUsableUnconfiguredClient() {
    invalid(() -> new GeminiSoundModels(null));
    invalid(() -> new GeminiSoundEmbeddingModels(null));
    invalid(
        () -> embedding(null, "pinned-v1", 3, "decoder-v1", Duration.ofSeconds(5), 65536, true));
    invalid(
        () ->
            embedding(
                new Endpoint(null, "model", "fixture-key"),
                "pinned-v1",
                3,
                "decoder-v1",
                Duration.ofSeconds(5),
                65536,
                true));
  }

  private static GeminiSoundModels.Configuration sound(
      Endpoint endpoint, String revision, Duration deadline, int cap, boolean local) {
    return new GeminiSoundModels.Configuration(endpoint, revision, deadline, cap, local);
  }

  private static GeminiSoundEmbeddingModels.Configuration embedding(
      Endpoint endpoint,
      String revision,
      int dimensions,
      String decoder,
      Duration deadline,
      int cap,
      boolean local) {
    return new GeminiSoundEmbeddingModels.Configuration(
        endpoint, revision, dimensions, decoder, deadline, cap, local);
  }

  private static void invalid(Runnable call) {
    var failure = assertThrows(TextModels.Failure.class, call::run);
    assertEquals("model_invalid_configuration", failure.code());
    assertNull(failure.getCause());
  }
}
