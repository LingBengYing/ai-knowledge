package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.QuestionFact;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleFactModelsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String QUESTION = "试运行时，方块是什么颜色？备份频率是多少？";

  @Test
  void extractsOnlyTargetTranscriptFactWithCompleteConditionalQuestion() throws Exception {
    var fact = new QuestionFact(1, "b".repeat(64), "value\nbackup\nfrequency\nday");
    try (var fixture = new Fixture()) {
      fixture.content.set(
          "{\"refused\":false,\"quotes\":[{\"evidence_id\":\"span-0\",\"quote\":\"试运行时每天备份一次。\"}]}");
      FactTextModels models = fixture.text;
      var result =
          models.extractFact(
              QUESTION, fact, List.of(new TextModels.Evidence("span-0", "试运行时每天备份一次。")));

      assertFalse(result.refused());
      assertEquals(List.of(new TextModels.Quote("span-0", "试运行时每天备份一次。")), result.quotes());
      var request = fixture.takeRequest();
      assertProtocol(request);
      var messages = request.path("messages");
      assertTargetPrompt(messages.get(0).path("content").asString());
      var data = JSON.readTree(messages.get(1).path("content").asString());
      assertTargetData(data, fact);
      assertEquals(3, data.size());
      assertEquals("span-0", data.path("evidence").get(0).path("evidence_id").asString());
      assertEquals("试运行时每天备份一次。", data.path("evidence").get(0).path("text").asString());
      assertEquals(fixture.text.revision(), models.revision());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void draftsAndIndependentlyVerifiesOnlyTargetFactFromOriginalFrame() throws Exception {
    var fact = new QuestionFact(0, "a".repeat(64), "color\nsquare");
    var image = image();
    try (var fixture = new Fixture()) {
      FactVisionModels models = fixture.vision;
      fixture.content.set("{\"refused\":false,\"claims\":[\"方块是蓝色的。\"]}");
      var draft = models.draftFact(QUESTION, fact, image);
      assertFalse(draft.refused());
      assertEquals(List.of("方块是蓝色的。"), draft.claims());
      var draftRequest = fixture.takeRequest();
      assertProtocol(draftRequest);
      assertTargetPrompt(draftRequest.path("messages").get(0).path("content").asString());
      var draftData = assertImageData(draftRequest, image);
      assertTargetData(draftData, fact);
      assertEquals(2, draftData.size(), "Only complete question and target fact, never caption");

      fixture.content.set("{\"complete\":true,\"support\":[{\"index\":0,\"supported\":true}]}");
      var verified = models.verifyFact(QUESTION, fact, image, draft.claims());
      assertTrue(verified.complete());
      assertEquals(List.of(true), verified.supported());
      var verifyRequest = fixture.takeRequest();
      assertProtocol(verifyRequest);
      assertTargetPrompt(verifyRequest.path("messages").get(0).path("content").asString());
      var verifyData = assertImageData(verifyRequest, image);
      assertTargetData(verifyData, fact);
      assertEquals(3, verifyData.size());
      assertEquals(0, verifyData.path("claims").get(0).path("index").asInt());
      assertEquals("方块是蓝色的。", verifyData.path("claims").get(0).path("claim").asString());
      assertEquals(fixture.vision.revision(), models.revision());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  private static void assertProtocol(JsonNode request) {
    assertEquals("synthetic-fact-model", request.path("model").asString());
    assertEquals("json_object", request.path("response_format").path("type").asString());
    assertFalse(request.path("stream").asBoolean());
    assertEquals(1, request.path("n").asInt());
    assertEquals(2, request.path("messages").size());
  }

  private static void assertTargetPrompt(String prompt) {
    assertTrue(prompt.contains("target canonical requirement"));
    assertTrue(prompt.contains("complete question"));
    assertTrue(prompt.contains("subjects, negations and conditions"));
    assertTrue(prompt.contains("Do not require other facts"));
    assertFalse(prompt.contains(QUESTION));
  }

  private static void assertTargetData(JsonNode data, QuestionFact fact) {
    assertEquals(QUESTION, data.path("question").asString());
    assertEquals(fact.id(), data.path("target_fact").path("id").asString());
    assertEquals(fact.ordinal(), data.path("target_fact").path("ordinal").asInt());
    assertEquals(
        fact.requirement(), data.path("target_fact").path("canonical_requirement").asString());
    assertEquals(3, data.path("target_fact").size());
  }

  private static JsonNode assertImageData(JsonNode request, VisualImage image) {
    var content = request.path("messages").get(1).path("content");
    assertEquals(2, content.size());
    assertEquals("text", content.get(0).path("type").asString());
    assertEquals("image_url", content.get(1).path("type").asString());
    var imageUrl = content.get(1).path("image_url");
    assertEquals("high", imageUrl.path("detail").asString());
    String url = imageUrl.path("url").asString();
    assertTrue(url.startsWith("data:image/png;base64,"));
    assertArrayEquals(image.content(), Base64.getDecoder().decode(url.substring(22)));
    return JSON.readTree(content.get(0).path("text").asString());
  }

  private static VisualImage image() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(
          ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", output));
    }
    return new VisualImage("image/png", bytes.toByteArray());
  }

  private static final class Fixture implements AutoCloseable {
    final LinkedBlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    final AtomicReference<String> content = new AtomicReference<>();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final HttpServer server;
    final OpenAiCompatibleModels text;
    final OpenAiCompatibleVisionModels vision;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/chat/completions", this::respond);
      server.start();
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "synthetic-fact-model",
              UUID.randomUUID().toString());
      text =
          new OpenAiCompatibleModels(
              new OpenAiCompatibleModels.Configuration(
                  endpoint, endpoint, endpoint, 3, Duration.ofSeconds(3), 65536, true));
      vision =
          new OpenAiCompatibleVisionModels(
              new OpenAiCompatibleVisionModels.Configuration(
                  endpoint, Duration.ofSeconds(3), 65536, true));
    }

    JsonNode takeRequest() {
      var request = requests.poll();
      assertNotNull(request);
      return request;
    }

    private void respond(HttpExchange exchange) throws IOException {
      requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
      byte[] response =
          JSON.writeValueAsBytes(
              Map.of(
                  "choices",
                  List.of(
                      Map.of(
                          "index",
                          0,
                          "finish_reason",
                          "stop",
                          "message",
                          Map.of("role", "assistant", "content", content.get())))));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    }

    @Override
    public void close() {
      text.close();
      vision.close();
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
