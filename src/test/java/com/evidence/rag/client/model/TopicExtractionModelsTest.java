package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TopicExtractionModelsTest {
  private static final String ORIGINAL = "项目名称：青榆灯塔项目\n灯塔项目仅供试运行，未经批准不得投产。";
  private static final List<TextModels.Evidence> EVIDENCE =
      List.of(new TextModels.Evidence("topic-1", ORIGINAL));
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<JsonNode> request = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private HttpServer server;
  private OpenAiCompatibleModels models;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          request.set(
              ModelHttpTransport.parseObject(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "fixture-model",
            "fixture-credential");
    models =
        new OpenAiCompatibleModels(
            new OpenAiCompatibleModels.Configuration(
                endpoint, endpoint, endpoint, 3, Duration.ofSeconds(2), 65536, true));
  }

  @AfterEach
  void stop() {
    if (models != null) {
      models.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void topicExtractionUsesIndependentStrictPromptAndPreservesCompleteOriginalQuote() {
    response.set(chat("stop", "topic-1", ORIGINAL));
    TextModels publicModels = models;
    String revision = publicModels.revision();
    var extracted = publicModels.extractTopic("灯塔", EVIDENCE);
    assertEquals(List.of(new TextModels.Quote("topic-1", ORIGINAL)), extracted.quotes());
    assertFalse(extracted.refused());
    assertEquals(1, requests.get());
    assertEquals(revision, publicModels.revision());
    assertEquals("java-topic-extraction-v1", TextModels.TOPIC_EXTRACTION_PROMPT_REVISION);
    String prompt = request.get().path("messages").get(0).path("content").stringValue();
    assertTrue(prompt.contains("Every quote must contain the complete topic query"));
    assertTrue(prompt.contains("case-insensitive whole-word"));
    assertTrue(prompt.contains("identifier boundaries"));
    assertTrue(prompt.contains("complete sentences or complete layout fields"));
    assertTrue(prompt.contains("conditions and negations"));
    assertTrue(prompt.contains("never concatenate"));
    assertTrue(prompt.contains("never translate"));
    assertTrue(prompt.contains("1200 Unicode code points"));
    assertTrue(prompt.contains("name-only"));
    assertTrue(prompt.contains("unrelated fields"));
    assertFalse(prompt.contains("青榆"));
    assertEquals(
        Set.of("model", "messages", "response_format", "stream", "n"),
        Set.copyOf(request.get().propertyNames()));
    assertFalse(request.get().has("max_tokens"));
    assertFalse(request.get().has("max_completion_tokens"));
    assertEquals("json_object", request.get().path("response_format").path("type").stringValue());
    assertFalse(request.get().path("stream").booleanValue());
    assertEquals(1, request.get().path("n").intValue());
    var payload =
        ModelHttpTransport.parseObject(
            request.get().path("messages").get(1).path("content").stringValue());
    assertEquals("灯塔", payload.path("question").stringValue());
    assertEquals(ORIGINAL, payload.path("evidence").get(0).path("text").stringValue());
  }

  @Test
  void genericExtractionRetainsOriginalPromptAndLargerLegacyQuoteLimit() throws Exception {
    String original = "Project " + "x".repeat(1201);
    response.set(chat("stop", "topic-1", original));
    var extracted =
        models.extract("Project", List.of(new TextModels.Evidence("topic-1", original)));
    assertEquals(original, extracted.quotes().getFirst().quote());
    String prompt = request.get().path("messages").get(0).path("content").stringValue();
    assertEquals(
        "4d7d88bd549e3382314a3bf01f40488b5c056df25a74c3653021a73f9db2e7b5",
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8))));
    assertEquals(1, requests.get());
  }

  @Test
  void topicExtractionRejectsOverlongQuoteRatherThanTruncatingIt() {
    String original = "灯塔" + "x".repeat(1199);
    response.set(chat("stop", "topic-1", original));
    assertEquals(
        "model_invalid_response",
        assertThrows(
                TextModels.Failure.class,
                () ->
                    models.extractTopic(
                        "灯塔", List.of(new TextModels.Evidence("topic-1", original))))
            .code());
    assertEquals(1, requests.get());
  }

  @Test
  void topicExtractionRejectsStitchedTextAcrossCandidates() {
    response.set(chat("stop", "topic-1", "灯塔项目\n条件：禁止投产。"));
    assertEquals(
        "model_invalid_response",
        assertThrows(
                TextModels.Failure.class,
                () ->
                    models.extractTopic(
                        "灯塔",
                        List.of(
                            new TextModels.Evidence("topic-1", "灯塔项目"),
                            new TextModels.Evidence("topic-2", "条件：禁止投产。"))))
            .code());
    assertEquals(1, requests.get());
  }

  @Test
  void topicExtractionStillRejectsLengthEvenWithOtherwiseValidJson() {
    response.set(chat("length", "topic-1", ORIGINAL));
    assertEquals(
        "model_invalid_response",
        assertThrows(TextModels.Failure.class, () -> models.extractTopic("灯塔", EVIDENCE)).code());
    assertEquals(1, requests.get());
  }

  @Test
  void defaultTopicMethodPreservesExistingAdapterCompatibility() {
    var expected =
        new TextModels.Extraction(List.of(new TextModels.Quote("topic-1", ORIGINAL)), false);
    var calls = new AtomicInteger();
    TextModels existingAdapter =
        new TextModels() {
          @Override
          public List<List<Double>> embed(List<String> texts) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<Ranked> rerank(String query, List<String> texts) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Extraction extract(String query, List<Evidence> evidence) {
            assertEquals("灯塔", query);
            assertSame(EVIDENCE, evidence);
            calls.incrementAndGet();
            return expected;
          }

          @Override
          public String revision() {
            return "fixture-revision";
          }
        };
    assertSame(expected, existingAdapter.extractTopic("灯塔", EVIDENCE));
    assertEquals(1, calls.get());
    assertEquals(0, requests.get());
  }

  private static String chat(String finishReason, String id, String quote) {
    return ModelHttpTransport.encodeJson(
        Map.of(
            "choices",
            List.of(
                Map.of(
                    "index",
                    0,
                    "finish_reason",
                    finishReason,
                    "message",
                    Map.of(
                        "role",
                        "assistant",
                        "content",
                        ModelHttpTransport.encodeJson(
                            Map.of(
                                "refused",
                                false,
                                "quotes",
                                List.of(Map.of("evidence_id", id, "quote", quote)))))))));
  }
}
