package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.AudioWaveform;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiSoundEmbeddingModelsTest {
  @Test
  void textAndWholeWavUseSeparateRequestsInOnePinnedSpace() throws Exception {
    var requests = new ArrayList<JsonNode>();
    var json = JsonMapper.builder().build();
    var response = new AtomicReference<>("{\"embedding\":{\"values\":[1,0.2,0]}}");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            assertEquals(
                "/v1beta/models/sound-embedding-fixture:embedContent",
                exchange.getRequestURI().toString());
            assertEquals("fixture-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    try {
      var endpoint =
          new Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "sound-embedding-fixture",
              "fixture-key");
      var config = config(endpoint, 3, true);
      try (var models = new GeminiSoundEmbeddingModels(config)) {
        assertEquals(config.revision(), models.revision());
        assertTrue(requests.isEmpty());
        assertEquals(List.of(1.0, (double) 0.2f, 0.0), models.embedText("铃声之后的完整尾部是什么？"));
        var wav =
            new AudioWaveform("a".repeat(64), "decoder-v1", 0, 2, new byte[] {1, 0, 0, 0}).wav();
        models.embedAudio(wav);
        assertEquals(2, requests.size());
        assertEquals(
            "铃声之后的完整尾部是什么？",
            requests.get(0).path("content").path("parts").get(0).path("text").asString());
        assertEquals(
            Base64.getEncoder().encodeToString(wav),
            requests
                .get(1)
                .path("content")
                .path("parts")
                .get(0)
                .path("inlineData")
                .path("data")
                .asString());
        for (var request : requests) {
          assertEquals(
              Set.of("content", "embedContentConfig"), new HashSet<>(request.propertyNames()));
          assertEquals(
              Set.of("outputDimensionality", "autoTruncate"),
              new HashSet<>(request.path("embedContentConfig").propertyNames()));
          assertFalse(request.path("embedContentConfig").path("autoTruncate").asBoolean());
        }
        assertThrows(TextModels.Failure.class, () -> models.embedText("中".repeat(1366)));
        assertThrows(TextModels.Failure.class, () -> models.embedAudio(new byte[44]));
        assertEquals(2, requests.size());
        response.set("{\"embedding\":{\"values\":[1,0,0],\"shape\":[3]}}");
        assertThrows(TextModels.Failure.class, () -> models.embedText("What sound?"));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void lowDimensionsRequireExplicitLiteralLoopbackAndProfileIsIndependentOfOldAudio() {
    var remote =
        new Endpoint(URI.create("https://model.invalid"), "explicit-model", "synthetic-key");
    assertThrows(TextModels.Failure.class, () -> config(remote, 3, true));
    var config = config(remote, 128, false);
    var old =
        new GeminiAudioEmbeddingModels.Configuration(
            remote, "pinned-v1", 128, "decoder-v1", Duration.ofSeconds(3), 65536, false);
    try (var oldClient = new GeminiAudioEmbeddingModels(old)) {
      assertNotEquals(oldClient.revision(), config.revision());
    }
    assertEquals(
        config.revision(),
        config(new Endpoint(remote.baseUrl(), remote.model(), "rotated"), 128, false).revision());
    assertNotEquals(config.revision(), config(remote, 129, false).revision());
    assertEquals("Configuration[redacted]", config.toString());
  }

  private static GeminiSoundEmbeddingModels.Configuration config(
      Endpoint endpoint, int dimensions, boolean local) {
    return new GeminiSoundEmbeddingModels.Configuration(
        endpoint, "pinned-v1", dimensions, "decoder-v1", Duration.ofSeconds(3), 65536, local);
  }
}
