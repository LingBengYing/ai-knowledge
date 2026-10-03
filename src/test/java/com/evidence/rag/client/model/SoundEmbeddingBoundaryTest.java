package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.AudioWaveform;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SoundEmbeddingBoundaryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final byte[] WAV =
      new AudioWaveform("a".repeat(64), "decoder-v1", 0, 2, new byte[] {1, 0, 0, 0}).wav();

  @Test
  void rejectsEveryNoncanonicalWavHeaderAndTruncatedOrOversizedSampleStreamBeforeDispatch()
      throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (int offset : new int[] {0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40}) {
        byte[] malformed = WAV.clone();
        malformed[offset] ^= 1;
        failure("model_invalid_input", () -> models.embedAudio(malformed));
      }
      for (byte[] malformed : Arrays.asList(null, new byte[45], new byte[47], new byte[960046])) {
        failure("model_invalid_input", () -> models.embedAudio(malformed));
      }
      assertEquals(0, fixture.calls.get());
      assertEquals(List.of(1.0, 0.0, 0.0), models.embedAudio(WAV));
      assertEquals(1, fixture.calls.get());
    }
  }

  @Test
  void wholeThirtySecondWavAndCompleteBoundaryQuestionAreNeverSampledOrPrefixed() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      byte[] pcm = new byte[960000];
      pcm[pcm.length - 1] = 21;
      byte[] complete = new AudioWaveform("a".repeat(64), "decoder-v1", 0, 480000, pcm).wav();
      models.embedAudio(complete);
      byte[] actual =
          Base64.getDecoder()
              .decode(
                  fixture
                      .request
                      .get()
                      .path("content")
                      .path("parts")
                      .get(0)
                      .path("inlineData")
                      .path("data")
                      .asString());
      assertTrue(Arrays.equals(complete, actual));
      String question = "x".repeat(4094) + "\n?";
      models.embedText(question);
      assertEquals(
          question,
          fixture.request.get().path("content").path("parts").get(0).path("text").asString());
      assertFalse(fixture.request.get().has("taskType"));
      assertFalse(
          fixture.request.get().path("embedContentConfig").path("autoTruncate").asBoolean());
    }
  }

  @Test
  void strictEmbeddingEnvelopeDimensionsAndFiniteNonzeroFloat32CoordinatesAreRequired()
      throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (String response :
          List.of(
              "{}",
              "{\"embedding\":[]}",
              "{\"embedding\":{}}",
              "{\"embedding\":{\"values\":[1,0,0]},\"candidate\":[]}",
              "{\"embedding\":{\"values\":{}}}",
              "{\"embedding\":{\"values\":[1,0]}}",
              "{\"embedding\":{\"values\":[\"1\",0,0]}}",
              "{\"embedding\":{\"values\":[1e400,0,0]}}",
              "{\"embedding\":{\"values\":[3.5e38,0,0]}}",
              "{\"embedding\":{\"values\":[0,0,-0.0]}}",
              "{\"embedding\":{\"values\":[1e-100,0,0]}}",
              "{\"embedding\":{\"values\":[1,0,0],\"values\":[0,1,0]}}")) {
        fixture.response.set(response);
        failure("model_invalid_response", () -> models.embedText("What sound?"));
      }
      fixture.response.set(
          "{\"embedding\":{\"values\":[0.2,-0.0,-1]},\"usageMetadata\":{\"promptTokenCount\":1}}");
      assertEquals(List.of((double) 0.2f, 0.0, -1.0), models.embedText("What sound?"));
    }
  }

  @Test
  void interruptionAndClosePreventNewRequestsAndPreserveSafeFailureCodes() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      Thread.currentThread().interrupt();
      try {
        failure("model_interrupted", () -> models.embedAudio(WAV));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      models.close();
      failure("model_closed", () -> models.embedText("What?"));
      assertEquals(0, fixture.calls.get());
    }
  }

  private static void failure(String expected, Runnable call) {
    var failure = assertThrows(TextModels.Failure.class, call::run);
    assertEquals(expected, failure.code());
    assertNull(failure.getCause());
  }

  private static final class Fixture implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final AtomicReference<String> response =
        new AtomicReference<>("{\"embedding\":{\"values\":[1,0,0]}}");

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1beta/models/embedding-fixture:embedContent",
          exchange -> {
            try (exchange) {
              calls.incrementAndGet();
              request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
              byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            }
          });
      server.start();
    }

    GeminiSoundEmbeddingModels models() {
      return new GeminiSoundEmbeddingModels(
          new GeminiSoundEmbeddingModels.Configuration(
              new Endpoint(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                  "embedding-fixture",
                  "fixture-key"),
              "pinned-v1",
              3,
              "decoder-v1",
              Duration.ofSeconds(5),
              65536,
              true));
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
