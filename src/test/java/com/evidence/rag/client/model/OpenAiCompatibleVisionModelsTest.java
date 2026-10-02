package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.VisualImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleVisionModelsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void draftsOrderedFactsFromTheOriginalPngAndCompleteQuestionWithoutCaption() throws Exception {
    String question = "Which object is blue, and where is the square? 😀";
    List<String> claims = List.of("The circle is blue.", "The square is right of the circle.");
    var png = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", png));
    var image = new VisualImage("image/png", png.toByteArray());
    String key = UUID.randomUUID().toString();
    var requests = new LinkedBlockingQueue<Request>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", exchange -> respond(exchange, requests, claims));
    server.start();
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/visual/v1"),
            "synthetic-vision-model",
            key);
    try (var models =
        new OpenAiCompatibleVisionModels(
            new OpenAiCompatibleVisionModels.Configuration(
                endpoint, Duration.ofSeconds(3), 65536, true))) {
      assertTrue(requests.isEmpty(), "Construction must not probe a provider");
      var result = models.draft(question, image);

      assertFalse(result.refused());
      assertEquals(claims, result.claims());
      assertThrows(UnsupportedOperationException.class, result.claims()::clear);
      var request = requests.poll();
      assertNotNull(request);
      assertTrue(requests.isEmpty(), "One operation must send exactly one request");
      assertEquals("/visual/v1/chat/completions", request.path());
      assertEquals("Bearer " + key, request.authorization());
      assertEquals("application/json", request.contentType());
      assertEquals("synthetic-vision-model", request.body().path("model").asString());
      assertEquals("json_object", request.body().path("response_format").path("type").asString());
      assertFalse(request.body().path("stream").asBoolean());
      assertEquals(1, request.body().path("n").asInt());
      var messages = request.body().path("messages");
      assertEquals(2, messages.size());
      assertEquals("system", messages.get(0).path("role").asString());
      assertFalse(messages.get(0).path("content").asString().contains(question));
      assertEquals("user", messages.get(1).path("role").asString());
      var content = messages.get(1).path("content");
      assertEquals(2, content.size());
      assertEquals("text", content.get(0).path("type").asString());
      var data = JSON.readTree(content.get(0).path("text").asString());
      assertEquals(question, data.path("question").asString());
      assertEquals(1, data.size(), "Draft receives the complete question, never a caption");
      assertEquals("image_url", content.get(1).path("type").asString());
      var source = content.get(1).path("image_url");
      assertEquals("high", source.path("detail").asString());
      String url = source.path("url").asString();
      assertTrue(url.startsWith("data:image/png;base64,"));
      assertArrayEquals(png.toByteArray(), Base64.getDecoder().decode(url.substring(22)));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void describesOriginalJpegButVerifiesEveryClaimIndependentlyInRequestOrder() throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "jpeg", bytes));
    var image = new VisualImage("image/jpeg", bytes.toByteArray());
    String misleadingRecall = "An unrelated yellow triangle.";
    String question = "What color is the circle?\nWhere is the square?\t😀";
    List<String> claims = List.of("The circle is blue.", "The square is right of the circle.");
    try (var fixture = new Fixture()) {
      fixture.response.set(JSON.writeValueAsString(Map.of("recall_text", misleadingRecall)));
      assertEquals(misleadingRecall, fixture.models.describe(image).recallText());
      var descriptionRequest = fixture.requests.poll();
      assertNotNull(descriptionRequest);
      var descriptionData =
          JSON.readTree(
              descriptionRequest
                  .body()
                  .path("messages")
                  .get(1)
                  .path("content")
                  .get(0)
                  .path("text")
                  .asString());
      assertEquals(0, descriptionData.size());
      assertImage(descriptionRequest, "image/jpeg", bytes.toByteArray());

      fixture.response.set(
          "{\"complete\":true,\"support\":[{\"index\":1,\"supported\":false},{\"index\":0,\"supported\":true}]}");
      var result = fixture.models.verify(question, image, claims);
      assertTrue(result.complete());
      assertEquals(List.of(true, false), result.supported());
      assertThrows(UnsupportedOperationException.class, result.supported()::clear);
      var verificationRequest = fixture.requests.poll();
      assertNotNull(verificationRequest);
      assertImage(verificationRequest, "image/jpeg", bytes.toByteArray());
      var messages = verificationRequest.body().path("messages");
      var data = JSON.readTree(messages.get(1).path("content").get(0).path("text").asString());
      assertEquals(question, data.path("question").asString());
      assertEquals(2, data.size());
      assertEquals(2, data.path("claims").size());
      for (int index = 0; index < claims.size(); index++) {
        assertEquals(index, data.path("claims").get(index).path("index").asInt());
        assertEquals(claims.get(index), data.path("claims").get(index).path("claim").asString());
      }
      assertFalse(verificationRequest.body().toString().contains(misleadingRecall));
      assertFalse(messages.get(0).path("content").asString().contains(question));
      assertTrue(fixture.requests.isEmpty(), "Describe and verify each send exactly one request");
      assertEquals("Verification[redacted]", result.toString());
    }
  }

  @Test
  void rejectsMissingDuplicateNegativeAndNonBooleanSupportWithoutRetry() throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", bytes));
    var image = new VisualImage("image/png", bytes.toByteArray());
    try (var fixture = new Fixture()) {
      for (String response :
          List.of(
              "{\"complete\":true,\"support\":[{\"index\":0,\"supported\":true}]}",
              "{\"complete\":true,\"support\":[{\"index\":0,\"supported\":true},{\"index\":0,\"supported\":true}]}",
              "{\"complete\":true,\"support\":[{\"index\":-1,\"supported\":true},{\"index\":1,\"supported\":true}]}",
              "{\"complete\":true,\"support\":[{\"index\":0,\"supported\":\"true\"},{\"index\":1,\"supported\":true}]}")) {
        fixture.response.set(response);
        var failure =
            assertThrows(
                TextModels.Failure.class,
                () ->
                    fixture.models.verify(
                        "Both colors?", image, List.of("Blue circle.", "Red square.")));
        assertEquals("model_invalid_response", failure.code());
        assertNull(failure.getCause());
        assertNotNull(fixture.requests.poll());
        assertTrue(fixture.requests.isEmpty(), "Invalid protocol must not trigger a retry");
      }
    }
  }

  @Test
  void rejectsMalformedDraftAndLengthTruncationInsteadOfReturningPartialFacts() throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", bytes));
    var image = new VisualImage("image/png", bytes.toByteArray());
    try (var fixture = new Fixture()) {
      List<String> tooMany =
          java.util.stream.IntStream.range(0, 9).mapToObj(index -> "Claim " + index).toList();
      for (String response :
          List.of(
              "{\"refused\":true,\"claims\":[\"The circle is blue.\"]}",
              JSON.writeValueAsString(Map.of("refused", false, "claims", tooMany)))) {
        fixture.response.set(response);
        var failure =
            assertThrows(
                TextModels.Failure.class, () -> fixture.models.draft("All visible facts?", image));
        assertEquals("model_invalid_response", failure.code());
        assertNull(failure.getCause());
        assertNotNull(fixture.requests.poll());
        assertTrue(fixture.requests.isEmpty());
      }
      fixture.response.set("{\"refused\":false,\"claims\":[\"The circle is blue.\"]}");
      fixture.finishReason.set("length");
      var failure =
          assertThrows(
              TextModels.Failure.class, () -> fixture.models.draft("All visible facts?", image));
      assertEquals("model_invalid_response", failure.code());
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty(), "A truncated completion must not be retried");
    }
  }

  @Test
  void rejectsInvalidOriginalAndExcessClaimsBeforeAnyProviderRequest() throws Exception {
    var invalid = new VisualImage("image/png", new byte[] {1, 2, 3});
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", bytes));
    var valid = new VisualImage("image/png", bytes.toByteArray());
    try (var fixture = new Fixture()) {
      var imageFailure =
          assertThrows(
              TextModels.Failure.class, () -> fixture.models.draft("What is visible?", invalid));
      assertEquals("model_invalid_input", imageFailure.code());
      assertNull(imageFailure.getCause());
      var countFailure =
          assertThrows(
              TextModels.Failure.class,
              () ->
                  fixture.models.verify(
                      "All nine?",
                      valid,
                      java.util.stream.IntStream.range(0, 9)
                          .mapToObj(index -> "Fact " + index)
                          .toList()));
      assertEquals("model_invalid_input", countFailure.code());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void bindsRevisionToExplicitModelNotCredentialsAndRejectsLongerDeadlines() {
    var base = URI.create("https://example.invalid/v1");
    String key = UUID.randomUUID().toString();
    var first = new OpenAiCompatibleModels.Endpoint(base, "synthetic-v1", key);
    var rotated =
        new OpenAiCompatibleModels.Endpoint(base, "synthetic-v1", UUID.randomUUID().toString());
    var changed = new OpenAiCompatibleModels.Endpoint(base, "synthetic-v2", key);
    var config =
        new OpenAiCompatibleVisionModels.Configuration(first, Duration.ofSeconds(60), 65536, false);
    try (var a = new OpenAiCompatibleVisionModels(config);
        var b =
            new OpenAiCompatibleVisionModels(
                new OpenAiCompatibleVisionModels.Configuration(
                    rotated, Duration.ofSeconds(60), 65536, false));
        var c =
            new OpenAiCompatibleVisionModels(
                new OpenAiCompatibleVisionModels.Configuration(
                    changed, Duration.ofSeconds(60), 65536, false))) {
      assertEquals(a.revision(), b.revision());
      assertNotEquals(a.revision(), c.revision());
      assertTrue(a.revision().matches("java-vision-models-v1-[a-f0-9]{64}"));
      assertFalse(a.revision().contains(key));
      assertEquals("Configuration[redacted]", config.toString());
    }
    var failure =
        assertThrows(
            TextModels.Failure.class,
            () ->
                new OpenAiCompatibleVisionModels.Configuration(
                    first, Duration.ofSeconds(61), 65536, false));
    assertEquals("model_invalid_configuration", failure.code());
  }

  private static void assertImage(Request request, String mime, byte[] original) {
    var source = request.body().path("messages").get(1).path("content").get(1).path("image_url");
    String prefix = "data:" + mime + ";base64,";
    assertEquals("high", source.path("detail").asString());
    assertTrue(source.path("url").asString().startsWith(prefix));
    assertArrayEquals(
        original,
        Base64.getDecoder().decode(source.path("url").asString().substring(prefix.length())));
  }

  private static final class Fixture implements AutoCloseable {
    final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    final AtomicReference<String> response = new AtomicReference<>();
    final AtomicReference<String> finishReason = new AtomicReference<>("stop");
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    final OpenAiCompatibleVisionModels models;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/", exchange -> respondContent(exchange, requests, response.get(), finishReason.get()));
      server.start();
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "synthetic-vision-model",
              UUID.randomUUID().toString());
      models =
          new OpenAiCompatibleVisionModels(
              new OpenAiCompatibleVisionModels.Configuration(
                  endpoint, Duration.ofSeconds(3), 65536, true));
    }

    @Override
    public void close() {
      models.close();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private static void respond(
      HttpExchange exchange, LinkedBlockingQueue<Request> requests, List<String> claims)
      throws IOException {
    respondContent(
        exchange, requests, JSON.writeValueAsString(Map.of("refused", false, "claims", claims)));
  }

  private static void respondContent(
      HttpExchange exchange, LinkedBlockingQueue<Request> requests, String response)
      throws IOException {
    respondContent(exchange, requests, response, "stop");
  }

  private static void respondContent(
      HttpExchange exchange,
      LinkedBlockingQueue<Request> requests,
      String response,
      String finishReason)
      throws IOException {
    try (exchange) {
      byte[] input = exchange.getRequestBody().readNBytes(16 * 1024 * 1024 + 1);
      if (input.length > 16 * 1024 * 1024) {
        exchange.sendResponseHeaders(413, -1);
        return;
      }
      requests.add(
          new Request(
              exchange.getRequestURI().getPath(),
              exchange.getRequestHeaders().getFirst("Authorization"),
              exchange.getRequestHeaders().getFirst("Content-Type"),
              JSON.readTree(input)));
      byte[] reply =
          JSON.writeValueAsBytes(
              Map.of(
                  "choices",
                  List.of(
                      Map.of(
                          "index",
                          0,
                          "finish_reason",
                          finishReason,
                          "message",
                          Map.of("role", "assistant", "content", response)))));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, reply.length);
      exchange.getResponseBody().write(reply);
    }
  }

  private record Request(String path, String authorization, String contentType, JsonNode body) {
    @Override
    public String toString() {
      return "Request[redacted]";
    }
  }
}
