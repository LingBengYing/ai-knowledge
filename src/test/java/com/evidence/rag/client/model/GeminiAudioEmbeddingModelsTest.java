package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels.Configuration;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.tool.parser.AudioPcm;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiAudioEmbeddingModelsTest {
  private static final String MODEL = "synthetic-audio-embedding-v1";
  private static final String DECODER = "synthetic-decoder-v1";
  private static final String RESPONSE =
      "{\"embedding\":{\"values\":[1,0.1,-0.0]},\"usageMetadata\":{\"promptTokenCount\":3}}";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void embedsCompleteCanonicalWavWithFixedGoogleAuthenticationAndNoTruncation() throws Exception {
    try (var fixture = new Fixture()) {
      assertEquals(3, fixture.models.dimensions());
      assertEquals(DECODER, fixture.models.decoderRevision());
      assertTrue(fixture.models.revision().matches("java-audio-embedding-v1:[a-f0-9]{64}"));
      assertTrue(fixture.requests.isEmpty(), "Construction and metadata never dispatch");
      for (int samples : new int[] {1, 480000}) {
        var wav = wav(samples);
        var result = fixture.models.embed(wav);
        assertEquals(List.of(1.0, (double) 0.1f, 0.0), result);
        assertEquals(Double.doubleToLongBits(0.0), Double.doubleToLongBits(result.get(2)));
        assertThrows(UnsupportedOperationException.class, result::clear);
        var call = fixture.requests.poll();
        assertNotNull(call);
        assertEquals("/v1beta/models/" + MODEL + ":embedContent", call.path());
        assertEquals("synthetic-google-key", call.googleKey());
        assertNull(call.authorization());
        assertEquals(
            Set.of("content", "embedContentConfig"), new HashSet<>(call.body().propertyNames()));
        var content = call.body().path("content");
        assertEquals(Set.of("parts"), new HashSet<>(content.propertyNames()));
        var parts = content.path("parts");
        assertEquals(1, parts.size());
        assertEquals(Set.of("inlineData"), new HashSet<>(parts.get(0).propertyNames()));
        var data = parts.get(0).path("inlineData");
        assertEquals(Set.of("mimeType", "data"), new HashSet<>(data.propertyNames()));
        assertEquals("audio/wav", data.path("mimeType").asString());
        assertEquals(Base64.getEncoder().encodeToString(wav), data.path("data").asString());
        assertArrayEquals(wav, Base64.getDecoder().decode(data.path("data").asString()));
        var config = call.body().path("embedContentConfig");
        assertEquals(
            Set.of("outputDimensionality", "autoTruncate"), new HashSet<>(config.propertyNames()));
        assertEquals(3, config.path("outputDimensionality").asInt());
        assertTrue(config.path("autoTruncate").isBoolean());
        assertFalse(config.path("autoTruncate").asBoolean());
        assertTrue(fixture.requests.isEmpty());
      }
    }
  }

  @Test
  void rootUrlWithTrailingSlashUsesTheSameFixedGooglePathWithoutDoubleSlash() throws Exception {
    try (var fixture = new Fixture()) {
      var root =
          new Endpoint(
              URI.create(fixture.endpoint().baseUrl().toString() + "/"),
              MODEL,
              "synthetic-google-key");
      try (var client =
          new GeminiAudioEmbeddingModels(
              new Configuration(
                  root, "pinned-v1", 3, DECODER, Duration.ofSeconds(3), 65536, true))) {
        assertTrue(fixture.requests.isEmpty());
        assertEquals(List.of(1.0, (double) 0.1f, 0.0), client.embed(wav(1)));
        var call = fixture.requests.poll();
        assertNotNull(call);
        assertEquals("/v1beta/models/" + MODEL + ":embedContent", call.path());
        assertEquals("synthetic-google-key", call.googleKey());
        assertNull(call.authorization());
        assertTrue(fixture.requests.isEmpty());
      }
    }
  }

  @Test
  void profileBindsModelEndpointDecoderRevisionAndDimensionsButNotKeyOrBudgets() {
    var endpoint = new Endpoint(URI.create("https://audio.invalid"), MODEL, "synthetic-google-key");
    var original = configuration(endpoint, "pinned-v1", 3, DECODER);
    try (var first = new GeminiAudioEmbeddingModels(original);
        var keyChanged =
            new GeminiAudioEmbeddingModels(
                new Configuration(
                    new Endpoint(endpoint.baseUrl(), MODEL, "rotated-key"),
                    "pinned-v1",
                    3,
                    DECODER,
                    Duration.ofSeconds(8),
                    131072,
                    false))) {
      assertTrue(first.revision().matches("java-audio-embedding-v1:[a-f0-9]{64}"));
      assertEquals(first.revision(), keyChanged.revision());
      for (var changed :
          List.of(
              configuration(
                  new Endpoint(URI.create("https://other.invalid"), MODEL, "synthetic-google-key"),
                  "pinned-v1",
                  3,
                  DECODER),
              configuration(
                  new Endpoint(endpoint.baseUrl(), "another-model", "synthetic-google-key"),
                  "pinned-v1",
                  3,
                  DECODER),
              configuration(endpoint, "pinned-v2", 3, DECODER),
              configuration(endpoint, "pinned-v1", 4, DECODER),
              configuration(endpoint, "pinned-v1", 3, "synthetic-decoder-v2"))) {
        try (var next = new GeminiAudioEmbeddingModels(changed)) {
          assertNotEquals(first.revision(), next.revision());
        }
      }
      assertEquals("Configuration[redacted]", original.toString());
    }
  }

  @Test
  void rejectsUnpinnedVersionsBadRootPathsAndOutOfRangeBoundsOffline() {
    var endpoint = new Endpoint(URI.create("https://audio.invalid"), MODEL, "synthetic-google-key");
    for (String revision :
        new String[] {null, "", "latest", "DEFAULT", "unknown", "bad revision"}) {
      failure("model_invalid_configuration", () -> configuration(endpoint, revision, 3, DECODER));
      failure("model_invalid_configuration", () -> configuration(endpoint, "pinned", 3, revision));
    }
    for (String model : List.of("models/name", "../name", "name:embedContent", "has space")) {
      failure(
          "model_invalid_configuration",
          () ->
              configuration(
                  new Endpoint(endpoint.baseUrl(), model, endpoint.apiKey()),
                  "pinned",
                  3,
                  DECODER));
    }
    for (String root :
        List.of(
            "https://audio.invalid/v1beta",
            "https://audio.invalid?key=private",
            "http://127.0.0.1:1")) {
      failure(
          "model_invalid_configuration",
          () ->
              configuration(
                  new Endpoint(URI.create(root), MODEL, endpoint.apiKey()), "pinned", 3, DECODER));
    }
    for (int dimensions : new int[] {1, 3073}) {
      failure(
          "model_invalid_configuration",
          () -> configuration(endpoint, "pinned", dimensions, DECODER));
    }
    for (Duration deadline :
        new Duration[] {null, Duration.ofMillis(9), Duration.ofMillis(120001)}) {
      failure(
          "model_invalid_configuration",
          () -> new Configuration(endpoint, "pinned", 3, DECODER, deadline, 1024, false));
    }
    for (int bytes : new int[] {1023, 4194305}) {
      failure(
          "model_invalid_configuration",
          () ->
              new Configuration(
                  endpoint, "pinned", 3, DECODER, Duration.ofSeconds(3), bytes, false));
    }
    failure("model_invalid_configuration", () -> new GeminiAudioEmbeddingModels(null));
    assertEquals(2, configuration(endpoint, "pinned", 2, DECODER).dimensions());
    assertEquals(3072, configuration(endpoint, "pinned", 3072, DECODER).dimensions());
  }

  @Test
  void rejectsNoncanonicalTruncatedStereoWrongRateAndOverlongWaveformsBeforeDispatch()
      throws Exception {
    try (var fixture = new Fixture()) {
      var original = wav(4);
      for (byte[] invalid :
          new byte[][] {
            null,
            new byte[0],
            Arrays.copyOf(original, 44),
            Arrays.copyOf(original, original.length - 1),
            Arrays.copyOf(original, original.length + 2),
            new byte[960046]
          }) {
        failure("model_invalid_input", () -> fixture.models.embed(invalid));
      }
      for (int offset : new int[] {0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40}) {
        var corrupted = original.clone();
        corrupted[offset] ^= 1;
        failure("model_invalid_input", () -> fixture.models.embed(corrupted));
      }
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void rejectsUnknownSoftTensorAndMalformedVectorsWithoutRetry() throws Exception {
    try (var fixture = new Fixture()) {
      for (String invalid :
          List.of(
              "{}",
              "[]",
              "not json",
              "{\"embedding\":null}",
              "{\"embedding\":{\"values\":[1,0,0]},\"unknown\":1}",
              "{\"embedding\":{\"values\":[1,0,0],\"shape\":[3]}}",
              "{\"embedding\":{\"values\":{\"0\":1}}}",
              "{\"embedding\":{\"values\":[1,0]}}",
              "{\"embedding\":{\"values\":[1,0,0,0]}}",
              "{\"embedding\":{\"values\":[0,0,0]}}",
              "{\"embedding\":{\"values\":[1e-100,0,0]}}",
              "{\"embedding\":{\"values\":[1e40,0,0]}}",
              "{\"embedding\":{\"values\":[1e400,0,0]}}",
              "{\"embedding\":{\"values\":[\"1\",0,0]}}",
              "{\"embedding\":{\"values\":[null,0,0]}}",
              "{\"embedding\":{\"values\":[NaN,0,0]}}",
              "{\"embedding\":{},\"embedding\":{\"values\":[1,0,0]}}",
              RESPONSE + " {}")) {
        fixture.response.set(invalid);
        failure("model_invalid_response", () -> fixture.models.embed(wav(2)));
        assertNotNull(fixture.requests.poll());
        assertTrue(fixture.requests.isEmpty());
      }
      fixture.response.set("{\"embedding\":{\"values\":[1,0,0]}}");
      assertEquals(List.of(1.0, 0.0, 0.0), fixture.models.embed(wav(2)));
    }
  }

  @Test
  void boundsUpstreamErrorsResponsesAndCloseWithoutLeakingProviderDetails() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.status.set(503);
      fixture.response.set("private upstream detail");
      failure("model_http_failed", () -> fixture.models.embed(wav(2)));
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
      fixture.status.set(200);
      fixture.response.set("x".repeat(65537));
      failure("model_response_too_large", () -> fixture.models.embed(wav(2)));
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
      fixture.models.close();
      failure("model_closed", () -> fixture.models.embed(wav(2)));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void interruptedCallerRetainsCancellationAndSendsNoAudio() throws Exception {
    try (var fixture = new Fixture()) {
      Thread.currentThread().interrupt();
      try {
        failure("model_interrupted", () -> fixture.models.embed(wav(2)));
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(fixture.requests.isEmpty());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void sharedTransportRetainsBearerForTheOriginalModelEntry() throws Exception {
    try (var fixture = new Fixture();
        var transport = new ModelHttpTransport(Duration.ofSeconds(3), 65536, 65536)) {
      transport.post(fixture.endpoint(), "legacy", Map.of("model", "legacy-model"));
      var call = fixture.requests.poll();
      assertNotNull(call);
      assertEquals("Bearer synthetic-google-key", call.authorization());
      assertNull(call.googleKey());
      assertEquals("/legacy", call.path());
    }
  }

  private static Configuration configuration(
      Endpoint endpoint, String version, int dimensions, String decoder) {
    return new Configuration(
        endpoint, version, dimensions, decoder, Duration.ofSeconds(3), 65536, false);
  }

  private static byte[] wav(int samples) {
    var pcm = new byte[samples * 2];
    pcm[0] = 17;
    return AudioPcm.wav(pcm, 0, pcm.length);
  }

  private static void failure(String code, Runnable action) {
    var failure = assertThrows(TextModels.Failure.class, action::run);
    assertEquals(code, failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("private"));
    assertFalse(failure.toString().contains("synthetic-google-key"));
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicReference<String> response = new AtomicReference<>(RESPONSE);
    final AtomicInteger status = new AtomicInteger(200);
    final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final HttpServer server;
    final GeminiAudioEmbeddingModels models;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::respond);
      server.start();
      models =
          new GeminiAudioEmbeddingModels(
              new Configuration(
                  endpoint(), "pinned-v1", 3, DECODER, Duration.ofSeconds(3), 65536, true));
    }

    Endpoint endpoint() {
      return new Endpoint(
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          MODEL,
          "synthetic-google-key");
    }

    private void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        requests.add(
            new Request(
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                JSON.readTree(exchange.getRequestBody().readAllBytes())));
        byte[] payload = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), payload.length);
        exchange.getResponseBody().write(payload);
      }
    }

    @Override
    public void close() {
      models.close();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private record Request(String path, String googleKey, String authorization, JsonNode body) {
    @Override
    public String toString() {
      return "Request[redacted]";
    }
  }
}
