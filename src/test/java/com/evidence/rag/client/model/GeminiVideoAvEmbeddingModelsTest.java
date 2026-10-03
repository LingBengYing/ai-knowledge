package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.AudioWaveform;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiVideoAvEmbeddingModelsTest {
  @Test
  void threeCompleteInputsUseOnePinnedSpaceAndSeparateRequests() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      assertEquals(0, f.calls.get());
      assertEquals(
          List.of(0.5, -0.25, 1.0), f.embeddings.embedText("What moves, and what sounds?"));
      assertEquals(
          "What moves, and what sounds?",
          f.request.get().path("content").path("parts").get(0).path("text").asString());
      var clip = VideoAvClientFixture.clip(8 * 1024 * 1024);
      f.embeddings.embedVideo(clip);
      assertEquals(
          Base64.getEncoder().encodeToString(clip.content()),
          f.request
              .get()
              .path("content")
              .path("parts")
              .get(0)
              .path("inlineData")
              .path("data")
              .asString());
      assertEquals(
          "video/mp4",
          f.request
              .get()
              .path("content")
              .path("parts")
              .get(0)
              .path("inlineData")
              .path("mimeType")
              .asString());
      var audio = VideoAvClientFixture.audio();
      f.embeddings.embedAudio(audio);
      assertEquals(
          Base64.getEncoder().encodeToString(audio.wav()),
          f.request
              .get()
              .path("content")
              .path("parts")
              .get(0)
              .path("inlineData")
              .path("data")
              .asString());
      assertEquals(1, f.request.get().path("content").path("parts").size());
      assertFalse(f.request.get().path("embedContentConfig").path("autoTruncate").asBoolean());
      assertFalse(f.request.get().path("embedContentConfig").has("taskType"));
      assertEquals(
          3, f.request.get().path("embedContentConfig").path("outputDimensionality").asInt());
      assertEquals("/v1beta/models/av-fixture:embedContent", f.path.get());
      assertEquals("synthetic-key", f.key.get());
      assertNull(f.bearer.get());
      assertEquals(3, f.calls.get());
    }
  }

  @Test
  void rejectsWrongDimensionsZeroSoftTensorAndUntrustedEnvelope() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      for (String body :
          List.of(
              "{\"embedding\":{\"values\":[1,2]}}",
              "{\"embedding\":{\"values\":[0,0,0]}}",
              "{\"embedding\":{\"values\":[1,2,3],\"shape\":[3]}}",
              "{\"embedding\":{\"values\":[1,2,3]},\"extra\":true}",
              "{\"embedding\":{\"values\":[1,2,\"3\"]}}",
              "{\"embedding\":{\"values\":[1,2,1e50]}}",
              "{\"embedding\":{\"values\":[1,2,3]},\"usageMetadata\":[]}")) {
        f.vectorResponse.set(body);
        assertEquals(
            "model_invalid_response",
            assertThrows(TextModels.Failure.class, () -> f.embeddings.embedText("What?")).code());
      }
    }
  }

  @Test
  void mismatchedDecoderAndInvalidQuestionNeverReachProvider() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      var audio = new AudioWaveform("a".repeat(64), "other-decoder", 0, 2, new byte[4]);
      assertThrows(TextModels.Failure.class, () -> f.embeddings.embedAudio(audio));
      assertThrows(TextModels.Failure.class, () -> f.embeddings.embedAudio(null));
      assertThrows(TextModels.Failure.class, () -> f.embeddings.embedVideo(null));
      assertThrows(TextModels.Failure.class, () -> f.embeddings.embedText("中".repeat(1366)));
      assertEquals(0, f.calls.get());
    }
  }

  @Test
  void profileBindsDecoderModelDimensionAndVersionExcludingCredentials() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      var a =
          new GeminiVideoAvEmbeddingModels.Configuration(
              f.endpoint, "v1", 3, "decoder-v1", Duration.ofSeconds(2), 65536, true);
      var b =
          new GeminiVideoAvEmbeddingModels.Configuration(
              new Endpoint(f.endpoint.baseUrl(), f.endpoint.model(), "another-key"),
              "v1",
              3,
              "decoder-v1",
              Duration.ofSeconds(1),
              131072,
              true);
      assertEquals(a.revision(), b.revision());
      assertNotEquals(
          a.revision(),
          new GeminiVideoAvEmbeddingModels.Configuration(
                  f.endpoint, "v1", 3, "decoder-v2", Duration.ofSeconds(2), 65536, true)
              .revision());
      assertNotEquals(
          a.revision(),
          new GeminiVideoAvEmbeddingModels.Configuration(
                  f.endpoint, "v2", 3, "decoder-v1", Duration.ofSeconds(2), 65536, true)
              .revision());
      assertNotEquals(
          a.revision(),
          new GeminiVideoAvEmbeddingModels.Configuration(
                  f.endpoint, "v1", 4, "decoder-v1", Duration.ofSeconds(2), 65536, true)
              .revision());
      assertEquals("Configuration[redacted]", a.toString());
      assertEquals(0, f.calls.get());
    }
  }

  @Test
  void rejectsNonRootEndpointsImplicitRevisionAndNonProviderDimensions() {
    for (Endpoint endpoint :
        List.of(
            new Endpoint(URI.create("https://model.invalid/v1"), "explicit", "synthetic-key"),
            new Endpoint(URI.create("https://model.invalid"), "models/nested", "synthetic-key"))) {
      assertThrows(
          TextModels.Failure.class,
          () ->
              new GeminiVideoAvEmbeddingModels.Configuration(
                  endpoint, "v1", 768, "decoder-v1", Duration.ofSeconds(1), 65536, false));
    }
    var endpoint = new Endpoint(URI.create("https://model.invalid"), "explicit", "synthetic-key");
    assertThrows(
        TextModels.Failure.class,
        () ->
            new GeminiVideoAvEmbeddingModels.Configuration(
                endpoint, "latest", 768, "decoder-v1", Duration.ofSeconds(1), 65536, false));
    assertThrows(
        TextModels.Failure.class,
        () ->
            new GeminiVideoAvEmbeddingModels.Configuration(
                endpoint, "v1", 3, "decoder-v1", Duration.ofSeconds(1), 65536, false));
  }
}
