package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.model.domain.VisualImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real local HTTP protocol and Service acceptance, not cloud visual accuracy or corpus release. */
class VisualModelFlowHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION =
      "Name both shapes, their colors and their left-to-right order. Answer in English.";
  private static final String RECALL = "RECALL_ONLY_SYNTHETIC_MARKER: a green triangle.";
  private final String credential = UUID.randomUUID().toString();
  private final BlockingQueue<Map<String, Object>> replies = new LinkedBlockingQueue<>();
  private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
  private final List<OpenAiCompatibleVisionModels> clients = new ArrayList<>();
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();
  private HttpServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/v1/chat/completions", this::reply);
    server.start();
  }

  @AfterEach
  void stop() {
    clients.forEach(OpenAiCompatibleVisionModels::close);
    if (server != null) {
      server.stop(0);
    }
    executor.shutdownNow();
  }

  @Test
  void pngAndJpegAssessmentsUseOriginalBytesAndEveryFactWithoutCaptionAuthority() throws Exception {
    for (String format : List.of("png", "jpeg")) {
      var image = VisualSyntheticFixture.image(format);
      var adapter = client();
      replies.add(Map.of("recall_text", RECALL));
      assertEquals(RECALL, adapter.describe(image).recallText());
      assertImageRequest(take(), image);
      List<String> claims =
          List.of("The left shape is a blue circle.", "The right shape is a red square.");
      replies.add(Map.of("refused", false, "claims", claims));
      // Out-of-order provider rows must normalize to the original claim order.
      replies.add(
          Map.of(
              "complete",
              true,
              "support",
              List.of(
                  Map.of("index", 1, "supported", true), Map.of("index", 0, "supported", true))));
      var result = new VisualAssessmentService(adapter).assess(QUESTION, image);
      assertNull(result.refusalReason());
      assertEquals(claims, result.claims());
      assertEquals(image.sha256(), result.sourceSha256());
      assertEquals(adapter.revision(), result.modelRevision());
      assertFalse(result.policyRevision().isBlank());
      JsonNode draft = take(), verification = take();
      assertImageRequest(draft, image);
      assertImageRequest(verification, image);
      for (var request : List.of(draft, verification)) {
        String text = userText(request);
        assertTrue(text.contains(QUESTION));
        assertFalse(text.contains(RECALL), "Recall descriptions must not become proof");
      }
      for (String claim : claims) {
        assertTrue(
            userText(verification).contains(claim), "Every claim reaches image verification");
      }
      assertTrue(requests.isEmpty());
      assertTrue(replies.isEmpty());
    }
  }

  @Test
  void negativeImageAssessmentCannotReleaseAnyProposedFacts() throws Exception {
    var image = VisualSyntheticFixture.image("png");
    var adapter = client();
    replies.add(Map.of("recall_text", RECALL));
    adapter.describe(image);
    take();
    replies.add(Map.of("refused", false, "claims", List.of("There is a green triangle.")));
    replies.add(
        Map.of("complete", true, "support", List.of(Map.of("index", 0, "supported", false))));
    var result = new VisualAssessmentService(adapter).assess(QUESTION, image);
    assertNotNull(result.refusalReason());
    assertTrue(result.claims().isEmpty());
    assertImageRequest(take(), image);
    JsonNode verification = take();
    assertImageRequest(verification, image);
    assertTrue(userText(verification).contains("There is a green triangle."));
    assertFalse(userText(verification).contains(RECALL));
    assertTrue(requests.isEmpty());
  }

  @Test
  void incompleteQuestionCoverageAbstainsEvenWhenEachReturnedFactIsSupported() throws Exception {
    var image = VisualSyntheticFixture.image("jpeg");
    replies.add(Map.of("refused", false, "claims", List.of("The left shape is a blue circle.")));
    replies.add(
        Map.of("complete", false, "support", List.of(Map.of("index", 0, "supported", true))));
    var result = new VisualAssessmentService(client()).assess(QUESTION, image);
    assertNotNull(result.refusalReason());
    assertTrue(result.claims().isEmpty());
    assertImageRequest(take(), image);
    assertImageRequest(take(), image);
    assertTrue(requests.isEmpty());
  }

  private OpenAiCompatibleVisionModels client() {
    var adapter =
        new OpenAiCompatibleVisionModels(
            new OpenAiCompatibleVisionModels.Configuration(
                new Endpoint(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "synthetic-vision-model",
                    credential),
                Duration.ofSeconds(3),
                1_048_576,
                true));
    clients.add(adapter);
    return adapter;
  }

  private JsonNode take() throws InterruptedException {
    JsonNode request = requests.poll(3, TimeUnit.SECONDS);
    assertNotNull(request, "Expected one explicit vision HTTP request");
    return request;
  }

  private void assertImageRequest(JsonNode request, VisualImage image) {
    assertEquals("synthetic-vision-model", request.path("model").asString());
    assertFalse(request.path("stream").asBoolean());
    assertEquals("json_object", request.path("response_format").path("type").asString());
    var messages = request.path("messages");
    assertEquals("system", messages.get(0).path("role").asString());
    assertFalse(messages.get(0).path("content").asString().contains(QUESTION));
    int imageCount = 0;
    for (var message : messages) {
      if (!"user".equals(message.path("role").asString())) {
        continue;
      }
      for (var part : message.path("content")) {
        if ("image_url".equals(part.path("type").asString())) {
          imageCount++;
          assertEquals("high", part.path("image_url").path("detail").asString());
          String data = part.path("image_url").path("url").asString();
          String prefix = "data:" + image.mediaType() + ";base64,";
          assertTrue(data.startsWith(prefix));
          assertArrayEquals(
              image.content(), Base64.getDecoder().decode(data.substring(prefix.length())));
        }
      }
    }
    assertEquals(1, imageCount, "Exactly the original image is sent; no URL or caption stand-in");
  }

  private static String userText(JsonNode request) {
    var pieces = new ArrayList<String>();
    for (var message : request.path("messages")) {
      if ("user".equals(message.path("role").asString())) {
        for (var part : message.path("content")) {
          if ("text".equals(part.path("type").asString())) {
            pieces.add(part.path("text").asString());
          }
        }
      }
    }
    return String.join("\n", pieces);
  }

  private void reply(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"POST".equals(exchange.getRequestMethod())
          || !("Bearer " + credential)
              .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        exchange.sendResponseHeaders(400, -1);
        return;
      }
      requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
      var response = replies.poll();
      if (response == null) {
        exchange.sendResponseHeaders(503, -1);
        return;
      }
      byte[] body =
          JSON.writeValueAsString(
                  Map.of(
                      "choices",
                      List.of(
                          Map.of(
                              "index",
                              0,
                              "finish_reason",
                              "stop",
                              "message",
                              Map.of(
                                  "role",
                                  "assistant",
                                  "content",
                                  JSON.writeValueAsString(response))))))
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
