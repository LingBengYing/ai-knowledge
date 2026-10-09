package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import tools.jackson.databind.JsonNode;

class TextModelProtocolLogTest {
  private static final String PRIVATE_TEXT = "private-evidence-body";
  private static final String PRIVATE_ID = "private-evidence-id";
  private static final List<TextModels.Evidence> EVIDENCE =
      List.of(new TextModels.Evidence(PRIVATE_ID, PRIVATE_TEXT));
  private static final List<TextModels.SynthesisEvidence> SUPPORT =
      List.of(new TextModels.SynthesisEvidence(PRIVATE_ID, PRIVATE_TEXT, PRIVATE_TEXT));
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<JsonNode> request = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicBoolean requireProviderCompletionDefault = new AtomicBoolean();
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleModels.class);
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
          String output =
              requireProviderCompletionDefault.get()
                      && (request.get().has("max_tokens")
                          || request.get().has("max_completion_tokens"))
                  ? chat("length", "{private-incomplete-content")
                  : response.get();
          byte[] body = output.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/private-path"),
            "private-model",
            "private-credential");
    models =
        new OpenAiCompatibleModels(
            new OpenAiCompatibleModels.Configuration(
                endpoint, endpoint, endpoint, 3, Duration.ofSeconds(2), 65536, true));
    events.start();
    logger.addAppender(events);
  }

  @AfterEach
  void stop() {
    logger.detachAppender(events);
    events.stop();
    if (models != null) {
      models.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void distinguishesExactQuoteRejectionFromSuccessfulOuterJsonWithoutExposingBody() {
    response.set(chat("stop", extraction(PRIVATE_ID, "private-fabricated-quote")));
    var failure =
        assertThrows(TextModels.Failure.class, () -> models.extract("private-question", EVIDENCE));
    assertEquals("model_invalid_response", failure.code());
    assertEquals("模型调用或配置未通过安全校验。", failure.getMessage());
    String line = onlyLine("extract", "quote_not_exact");
    assertTrue(line.contains("finish_reason=stop"));
    assertTrue(line.contains("prompt_tokens=120 completion_tokens=2048 total_tokens=2168"));
    assertTrue(line.contains("reasoning_tokens=1990"));
    assertEquals(1, requests.get());
    assertStandardStructuredRequest();
  }

  @Test
  void identifiesLengthFinishBeforeParsingPartialContentAndPreservesFailureCode() {
    response.set(chat("length", "{private-incomplete-content"));
    assertEquals(
        "model_invalid_response",
        assertThrows(TextModels.Failure.class, () -> models.extract("private-question", EVIDENCE))
            .code());
    String line = onlyLine("extract", "finish_length");
    assertTrue(line.contains("finish_reason=length"));
    assertTrue(line.contains("content_characters=27"));
    assertTrue(line.contains("reasoning_characters=17"));
  }

  @Test
  void reportsFixedJsonQuoteAndEnvelopeReasonsWithNoRetry() {
    var cases = new LinkedHashMap<String, String>();
    cases.put("content_json", chat("stop", "private-not-json"));
    cases.put(
        "extraction_fields", chat("stop", "{\"refused\":false,\"quotes\":[],\"private-extra\":1}"));
    cases.put("quote_id", chat("stop", extraction("private-unknown-id", PRIVATE_TEXT)));
    cases.put("choices", "{\"choices\":[]}");
    cases.put(
        "index",
        chat("stop", extraction(PRIVATE_ID, PRIVATE_TEXT)).replace("\"index\":0", "\"index\":2"));
    cases.put(
        "message_role",
        chat("stop", extraction(PRIVATE_ID, PRIVATE_TEXT))
            .replace("\"assistant\"", "\"private-role\""));
    cases.put(
        "tool_calls",
        chat("stop", extraction(PRIVATE_ID, PRIVATE_TEXT))
            .replace("\"role\":\"assistant\"", "\"tool_calls\":[],\"role\":\"assistant\""));
    for (var entry : cases.entrySet()) {
      events.list.clear();
      response.set(entry.getValue());
      assertEquals(
          "model_invalid_response",
          assertThrows(TextModels.Failure.class, () -> models.extract("private-question", EVIDENCE))
              .code());
      onlyLine("extract", entry.getKey());
    }
    assertEquals(cases.size(), requests.get());
  }

  @Test
  void recordsAllThreeSuccessfulOperationsWithoutChangingProtocolOrRevision() {
    String revision = models.revision();
    response.set(chat("stop", extraction(PRIVATE_ID, PRIVATE_TEXT)));
    assertFalse(models.extract("private-question", EVIDENCE).refused());
    onlyLine("extract", "validated");
    events.list.clear();
    response.set(
        chat(
            "stop",
            ModelHttpTransport.encodeJson(
                Map.of(
                    "refused",
                    false,
                    "statements",
                    List.of(Map.of("text", PRIVATE_TEXT, "evidence_ids", List.of(PRIVATE_ID)))))));
    var synthesis = models.synthesize("private-question", SUPPORT);
    assertEquals(PRIVATE_TEXT, synthesis.statements().getFirst().text());
    onlyLine("synthesize", "validated");
    assertStandardStructuredRequest();
    events.list.clear();
    response.set(
        chat(
            "stop",
            ModelHttpTransport.encodeJson(
                Map.of(
                    "complete",
                    true,
                    "statements",
                    List.of(
                        Map.of(
                            "index",
                            0,
                            "supported",
                            true,
                            "contributing_evidence_ids",
                            List.of(PRIVATE_ID)))))));
    assertTrue(models.verifySynthesis("private-question", synthesis, SUPPORT));
    onlyLine("verify", "validated");
    assertStandardStructuredRequest();
    assertEquals(3, requests.get());
    assertEquals(revision, models.revision());
  }

  @Test
  void distinguishesLocalSynthesisInputFailureWithoutSendingRequest() {
    assertEquals(
        "model_invalid_input",
        assertThrows(
                TextModels.Failure.class,
                () ->
                    models.synthesize(
                        "private-question",
                        List.of(
                            new TextModels.SynthesisEvidence(
                                PRIVATE_ID, "private-absent", PRIVATE_TEXT))))
            .code());
    String line = onlyLine("synthesize", "input_evidence");
    assertTrue(line.contains("finish_reason=missing"));
    assertTrue(line.contains("content_characters=-1 reasoning_characters=-1"));
    assertEquals(0, requests.get());
  }

  @Test
  void replacesUntrustedFinishReasonAndUsageWithFixedValues() {
    response.set(
        chat("private-finish\nforged-log", extraction(PRIVATE_ID, PRIVATE_TEXT))
            .replace("\"prompt_tokens\":120", "\"prompt_tokens\":\"private-usage\"")
            .replace("\"completion_tokens\":2048", "\"completion_tokens\":-1")
            .replace("\"total_tokens\":2168", "\"total_tokens\":999999999999999999999999999999"));
    assertThrows(TextModels.Failure.class, () -> models.extract("private-question", EVIDENCE));
    String line = onlyLine("extract", "finish_reason");
    assertTrue(line.contains("finish_reason=other"));
    assertTrue(line.contains("prompt_tokens=-1 completion_tokens=-1 total_tokens=-1"));
  }

  @Test
  void identifiesRequestEncodingLimitSeparatelyFromLocalEvidenceValidation() {
    String context = PRIVATE_TEXT + "x".repeat(1_048_576);
    assertEquals(
        "model_invalid_input",
        assertThrows(
                TextModels.Failure.class,
                () ->
                    models.synthesize(
                        "private-question",
                        List.of(
                            new TextModels.SynthesisEvidence(PRIVATE_ID, PRIVATE_TEXT, context))))
            .code());
    onlyLine("synthesize", "request_invalid_input");
    assertEquals(0, requests.get());
  }

  @Test
  void distinguishesSynthesisAndVerificationResponseSchemaFailures() {
    response.set(chat("stop", "{\"private-schema\":true}"));
    assertEquals(
        "model_invalid_response",
        assertThrows(TextModels.Failure.class, () -> models.synthesize("private-question", SUPPORT))
            .code());
    onlyLine("synthesize", "synthesis_fields");
    events.list.clear();
    var synthesis =
        new TextModels.Synthesis(
            false, List.of(new TextModels.Statement(PRIVATE_TEXT, List.of(PRIVATE_ID))));
    assertEquals(
        "model_invalid_response",
        assertThrows(
                TextModels.Failure.class,
                () -> models.verifySynthesis("private-question", synthesis, SUPPORT))
            .code());
    onlyLine("verify", "verification_fields");
    assertEquals(2, requests.get());
  }

  @Test
  void loggingFailureCannotReplaceSuccessfulExtractionOrOriginalProtocolFailure() {
    var loggingFailures = new AtomicInteger();
    var filter =
        new TurboFilter() {
          @Override
          public FilterReply decide(
              Marker marker,
              Logger target,
              Level level,
              String format,
              Object[] parameters,
              Throwable throwable) {
            if (target.getName().equals(OpenAiCompatibleModels.class.getName())) {
              loggingFailures.incrementAndGet();
              throw new IllegalStateException("private-logging-failure");
            }
            return FilterReply.NEUTRAL;
          }
        };
    var context = logger.getLoggerContext();
    filter.setContext(context);
    filter.start();
    context.addTurboFilter(filter);
    try {
      assertAll(
          () -> {
            response.set(chat("stop", extraction(PRIVATE_ID, PRIVATE_TEXT)));
            assertFalse(models.extract("private-question", EVIDENCE).refused());
          },
          () -> {
            response.set(chat("stop", extraction(PRIVATE_ID, "private-fabricated-quote")));
            var failure =
                assertThrows(
                    TextModels.Failure.class, () -> models.extract("private-question", EVIDENCE));
            assertEquals("model_invalid_response", failure.code());
            assertEquals("模型调用或配置未通过安全校验。", failure.getMessage());
          });
      assertEquals(2, loggingFailures.get());
      assertEquals(2, requests.get());
      assertTrue(events.list.isEmpty());
    } finally {
      context.getTurboFilterList().remove(filter);
      filter.stop();
    }
  }

  @Test
  void usesProviderDefaultCompletionBudgetForAllGenerationOperationsWithoutRetries() {
    requireProviderCompletionDefault.set(true);
    var synthesis =
        new TextModels.Synthesis(
            false, List.of(new TextModels.Statement(PRIVATE_TEXT, List.of(PRIVATE_ID))));
    assertAll(
        () -> {
          response.set(chat("stop", extraction(PRIVATE_ID, PRIVATE_TEXT)));
          assertFalse(models.extract("private-question", EVIDENCE).refused());
          onlyLine("extract", "validated");
          assertStandardStructuredRequest();
          assertEquals(1, requests.get());
        },
        () -> {
          events.list.clear();
          response.set(
              chat(
                  "stop",
                  ModelHttpTransport.encodeJson(
                      Map.of(
                          "refused",
                          false,
                          "statements",
                          List.of(
                              Map.of(
                                  "text", PRIVATE_TEXT, "evidence_ids", List.of(PRIVATE_ID)))))));
          assertEquals(synthesis, models.synthesize("private-question", SUPPORT));
          onlyLine("synthesize", "validated");
          assertStandardStructuredRequest();
          assertEquals(2, requests.get());
        },
        () -> {
          events.list.clear();
          response.set(
              chat(
                  "stop",
                  ModelHttpTransport.encodeJson(
                      Map.of(
                          "complete",
                          true,
                          "statements",
                          List.of(
                              Map.of(
                                  "index",
                                  0,
                                  "supported",
                                  true,
                                  "contributing_evidence_ids",
                                  List.of(PRIVATE_ID)))))));
          assertTrue(models.verifySynthesis("private-question", synthesis, SUPPORT));
          onlyLine("verify", "validated");
          assertStandardStructuredRequest();
          assertEquals(3, requests.get());
        });
  }

  private void assertStandardStructuredRequest() {
    assertFalse(request.get().has("max_tokens"));
    assertFalse(request.get().has("max_completion_tokens"));
    assertEquals(
        Set.of("model", "messages", "response_format", "stream", "n"),
        Set.copyOf(request.get().propertyNames()));
    assertEquals("json_object", request.get().path("response_format").path("type").stringValue());
    assertFalse(request.get().path("stream").booleanValue());
    assertEquals(1, request.get().path("n").intValue());
  }

  private String onlyLine(String operation, String reason) {
    assertEquals(1, events.list.size(), "One terminal protocol event per generation operation");
    var event = events.list.getFirst();
    assertNull(event.getThrowableProxy());
    String line = event.getFormattedMessage();
    assertTrue(
        line.startsWith("model_protocol operation=" + operation + " reason=" + reason + " "), line);
    String phase =
        reason.startsWith("input_")
            ? "input"
            : reason.startsWith("request_") || reason.startsWith("transport_")
                ? "transport"
                : "response";
    assertTrue(line.contains(" phase=" + phase + " "), line);
    for (String forbidden : List.of("private-", "127.0.0.1", "Bearer", "forged-log", "\n")) {
      assertFalse(line.contains(forbidden), line);
    }
    return line;
  }

  private static String extraction(String id, String quote) {
    return ModelHttpTransport.encodeJson(
        Map.of("refused", false, "quotes", List.of(Map.of("evidence_id", id, "quote", quote))));
  }

  private static String chat(String finishReason, String content) {
    return ModelHttpTransport.encodeJson(
        Map.of(
            "id",
            "private-completion-id",
            "model",
            "private-model",
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
                        content,
                        "reasoning_content",
                        "private-reasoning"))),
            "usage",
            Map.of(
                "prompt_tokens",
                120,
                "completion_tokens",
                2048,
                "total_tokens",
                2168,
                "completion_tokens_details",
                Map.of("reasoning_tokens", 1990))));
  }
}
