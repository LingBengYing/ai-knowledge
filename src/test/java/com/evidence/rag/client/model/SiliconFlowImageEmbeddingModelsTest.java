package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels.Configuration;
import com.evidence.rag.model.domain.VisualImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SiliconFlowImageEmbeddingModelsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MODEL = "synthetic/image-embedding-v1";
  private static final String RESPONSE =
      "{\"object\":\"list\",\"model\":\""
          + MODEL
          + "\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0.1,-0.0]}],"
          + "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}";

  @Test
  void sendsEachOriginalPngAndJpegAsOneBareBase64InputAndReturnsCanonicalVector() throws Exception {
    try (var fixture = new Fixture()) {
      assertEquals(3, fixture.models.dimensions());
      assertTrue(fixture.models.revision().matches("java-image-embedding-v1:[a-f0-9]{64}"));
      assertTrue(fixture.requests.isEmpty(), "Construction and revision must be offline");
      for (var input : List.of(image("png", 0x2244AA), image("jpeg", 0xAA5522))) {
        var result = fixture.models.embed(input);
        assertEquals(List.of(1.0, (double) 0.1f, 0.0), result);
        assertEquals(Double.doubleToLongBits(0.0), Double.doubleToLongBits(result.get(2)));
        assertThrows(UnsupportedOperationException.class, result::clear);
        var request = fixture.requests.poll();
        assertNotNull(request);
        assertEquals("/image/v1/embeddings", request.path());
        assertEquals("Bearer " + fixture.key, request.authorization());
        assertEquals("application/json", request.contentType());
        var body = request.body();
        assertEquals(
            Set.of("model", "input", "encoding_format", "dimensions"),
            new HashSet<>(body.propertyNames()));
        assertEquals(MODEL, body.path("model").asString());
        assertEquals("float", body.path("encoding_format").asString());
        assertTrue(body.path("dimensions").isIntegralNumber());
        assertEquals(3, body.path("dimensions").asInt());
        assertTrue(body.path("input").isObject());
        assertEquals(Set.of("image"), new HashSet<>(body.path("input").propertyNames()));
        String encoded = body.path("input").path("image").asString();
        assertEquals(Base64.getEncoder().encodeToString(input.content()), encoded);
        assertArrayEquals(input.content(), Base64.getDecoder().decode(encoded));
        assertFalse(encoded.startsWith("data:"));
        assertTrue(fixture.requests.isEmpty(), "Exactly one request for each original image");
      }
    }
  }

  @Test
  void profileBindsEndpointModelExplicitRevisionAndDimensionsButNotCredentials() {
    var endpoint = new Endpoint(URI.create("https://image.invalid/v1"), MODEL, "synthetic-key");
    var configuration = configuration(endpoint, "model-2026-10", 3);
    try (var first = new SiliconFlowImageEmbeddingModels(configuration);
        var keyChanged =
            new SiliconFlowImageEmbeddingModels(
                configuration(
                    new Endpoint(endpoint.baseUrl(), MODEL, "different-key"),
                    "model-2026-10",
                    3))) {
      assertTrue(first.revision().matches("java-image-embedding-v1:[a-f0-9]{64}"));
      assertEquals(first.revision(), keyChanged.revision());
      for (var changed :
          List.of(
              configuration(
                  new Endpoint(URI.create("https://other.invalid/v1"), MODEL, "synthetic-key"),
                  "model-2026-10",
                  3),
              configuration(
                  new Endpoint(endpoint.baseUrl(), "another-model", "synthetic-key"),
                  "model-2026-10",
                  3),
              configuration(endpoint, "model-2026-11", 3),
              configuration(endpoint, "model-2026-10", 4))) {
        try (var next = new SiliconFlowImageEmbeddingModels(changed)) {
          assertNotEquals(first.revision(), next.revision());
        }
      }
      assertEquals("Configuration[redacted]", configuration.toString());
      assertFalse(first.toString().contains(endpoint.baseUrl().toString()));
      assertFalse(first.toString().contains("synthetic-key"));
    }
  }

  @Test
  void rejectsInvalidConfigurationWithSanitizedErrorsBeforeAnyConnection() {
    var endpoint = new Endpoint(URI.create("https://image.invalid/v1"), MODEL, "synthetic-key");
    for (String revision :
        new String[] {null, "", "latest", "DEFAULT", "unknown", " bad", "a\n", "x".repeat(161)}) {
      safeFailure("model_invalid_configuration", () -> configuration(endpoint, revision, 3));
    }
    for (int dimensions : new int[] {1, 8193}) {
      safeFailure(
          "model_invalid_configuration", () -> configuration(endpoint, "pinned", dimensions));
    }
    for (Duration deadline :
        new Duration[] {null, Duration.ofMillis(9), Duration.ofMillis(120001)}) {
      safeFailure(
          "model_invalid_configuration",
          () -> new Configuration(endpoint, "pinned", 3, deadline, 1024, false));
    }
    for (int bytes : new int[] {1023, 4 * 1024 * 1024 + 1}) {
      safeFailure(
          "model_invalid_configuration",
          () -> new Configuration(endpoint, "pinned", 3, Duration.ofSeconds(3), bytes, false));
    }
    for (Endpoint invalid :
        new Endpoint[] {
          null,
          new Endpoint(URI.create("http://127.0.0.1:1/v1"), MODEL, "synthetic-key"),
          new Endpoint(URI.create("https://image.invalid/v1?private=1"), MODEL, "synthetic-key"),
          new Endpoint(URI.create("https://image.invalid/v1"), "bad model", "synthetic-key"),
          new Endpoint(URI.create("https://image.invalid/v1"), MODEL, "bad key")
        }) {
      safeFailure("model_invalid_configuration", () -> configuration(invalid, "pinned", 3));
    }
    safeFailure("model_invalid_configuration", () -> new SiliconFlowImageEmbeddingModels(null));
    assertEquals(2, configuration(endpoint, "pinned", 2).dimensions());
    assertEquals(8192, configuration(endpoint, "pinned", 8192).dimensions());
    assertEquals(
        Duration.ofMillis(10),
        new Configuration(endpoint, "pinned", 3, Duration.ofMillis(10), 1024, false).deadline());
    assertEquals(
        Duration.ofMillis(120000),
        new Configuration(endpoint, "pinned", 3, Duration.ofMillis(120000), 4 * 1024 * 1024, false)
            .deadline());
  }

  @Test
  void rejectsMissingInvalidAndMismatchedOriginalImagesBeforeSending() throws Exception {
    try (var fixture = new Fixture()) {
      for (VisualImage invalid :
          new VisualImage[] {
            null,
            new VisualImage("image/png", "not an original image".getBytes(StandardCharsets.UTF_8)),
            new VisualImage("image/jpeg", image("png", 0x112233).content())
          }) {
        safeFailure("model_invalid_input", () -> fixture.models.embed(invalid));
      }
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void rejectsWrongIdentityShapeIndicesDimensionsAndFloat32ValuesWithoutRetry() throws Exception {
    var input = image("png", 0x339966);
    try (var fixture = new Fixture()) {
      for (String invalid :
          List.of(
              "{}",
              "[]",
              "not json",
              RESPONSE.replace("\"object\":\"list\"", "\"object\":\"embedding\""),
              RESPONSE.replace(MODEL, "wrong-model"),
              RESPONSE.replace("\"data\":[", "\"data\":null,\"ignored\":["),
              RESPONSE.replace("\"object\":\"embedding\"", "\"object\":\"list\""),
              RESPONSE.replace("\"index\":0", "\"index\":\"0\""),
              RESPONSE.replace("\"index\":0", "\"index\":0.0"),
              RESPONSE.replace("\"index\":0", "\"index\":-1"),
              RESPONSE.replace("\"index\":0", "\"index\":1"),
              RESPONSE.replace("\"index\":0", "\"index\":2147483648"),
              RESPONSE.replace("[1,0.1,-0.0]", "[1,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[1,0,0,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[0,0,-0.0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[1e-100,0,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[1e40,0,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[1e400,0,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[\"1\",0,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[null,0,0]"),
              RESPONSE.replace("[1,0.1,-0.0]", "[NaN,0,0]"),
              RESPONSE.replace("\"index\":0", "\"index\":0,\"index\":0"),
              RESPONSE + " {}",
              "{\"object\":\"list\",\"model\":\"" + MODEL + "\",\"data\":[]}",
              "{\"object\":\"list\",\"model\":\"" + MODEL + "\",\"data\":[null]}",
              "{\"object\":\"list\",\"model\":\""
                  + MODEL
                  + "\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]},{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}]}")) {
        fixture.response.set(invalid);
        safeFailure("model_invalid_response", () -> fixture.models.embed(input));
        assertNotNull(fixture.requests.poll());
        assertTrue(fixture.requests.isEmpty(), "Malformed response must not trigger a retry");
      }
    }
  }

  @Test
  void boundsUpstreamErrorsAndResponseBytesAndClosesWithoutRetry() throws Exception {
    var input = image("jpeg", 0x883322);
    try (var fixture = new Fixture()) {
      fixture.status.set(503);
      fixture.response.set("private upstream detail");
      safeFailure("model_http_failed", () -> fixture.models.embed(input));
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
      fixture.status.set(200);
      fixture.response.set("x".repeat(65537));
      safeFailure("model_response_too_large", () -> fixture.models.embed(input));
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
      fixture.models.close();
      safeFailure("model_closed", () -> fixture.models.embed(input));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void anAlreadyCancelledBuildNeverDispatchesAnOriginalImage() throws Exception {
    var input = image("png", 0x224466);
    try (var fixture = new Fixture()) {
      assertFalse(Thread.currentThread().isInterrupted());
      Thread.currentThread().interrupt();
      try {
        safeFailure("model_interrupted", () -> fixture.models.embed(input));
        assertTrue(
            Thread.currentThread().isInterrupted(), "The caller retains cancellation ownership");
        assertTrue(fixture.requests.isEmpty());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void providerCannotSubstituteAnObjectForVectorsOrExtendTheEmbeddingRow() throws Exception {
    var input = image("jpeg", 0x775533);
    try (var fixture = new Fixture()) {
      for (String invalid :
          List.of(
              "{\"object\":\"list\",\"model\":\"" + MODEL + "\",\"data\":{\"embedding\":[1,0,0]}}",
              RESPONSE.replace("\"index\":0", "\"index\":0,\"private_provider_field\":true"),
              RESPONSE.replace("[1,0.1,-0.0]", "{\"0\":1,\"1\":0,\"2\":0}"))) {
        fixture.response.set(invalid);
        safeFailure("model_invalid_response", () -> fixture.models.embed(input));
        assertNotNull(fixture.requests.poll());
        assertTrue(
            fixture.requests.isEmpty(),
            "Invalid provider envelopes do not retry or produce a vector");
      }
    }
  }

  private static Configuration configuration(Endpoint endpoint, String revision, int dimensions) {
    return new Configuration(endpoint, revision, dimensions, Duration.ofSeconds(3), 65536, false);
  }

  private static void safeFailure(String code, Runnable action) {
    var failure = assertThrows(TextModels.Failure.class, action::run);
    assertEquals(code, failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("private"));
    assertFalse(failure.toString().contains("synthetic-key"));
  }

  private static VisualImage image(String format, int rgb) throws IOException {
    var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, rgb);
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, format, bytes));
    return new VisualImage(format.equals("png") ? "image/png" : "image/jpeg", bytes.toByteArray());
  }

  private static final class Fixture implements AutoCloseable {
    final String key = UUID.randomUUID().toString();
    final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    final AtomicReference<String> response = new AtomicReference<>(RESPONSE);
    final AtomicInteger status = new AtomicInteger(200);
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final HttpServer server;
    final SiliconFlowImageEmbeddingModels models;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::respond);
      server.start();
      models =
          new SiliconFlowImageEmbeddingModels(
              new Configuration(
                  new Endpoint(
                      URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image/v1"),
                      MODEL,
                      key),
                  "synthetic-image-model-2026-10",
                  3,
                  Duration.ofSeconds(3),
                  65536,
                  true));
    }

    private void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        byte[] bytes = exchange.getRequestBody().readNBytes(14 * 1024 * 1024 + 1);
        if (bytes.length > 14 * 1024 * 1024) {
          exchange.sendResponseHeaders(413, -1);
          return;
        }
        requests.add(
            new Request(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                JSON.readTree(bytes)));
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

  private record Request(String path, String authorization, String contentType, JsonNode body) {
    @Override
    public String toString() {
      return "Request[redacted]";
    }
  }
}
