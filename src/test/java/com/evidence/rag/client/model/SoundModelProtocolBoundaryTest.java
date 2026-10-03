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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SoundModelProtocolBoundaryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AudioWaveform WAVE =
      new AudioWaveform("a".repeat(64), "decoder-v1", 0, 2, new byte[] {1, 0, 0, 0});

  @Test
  void responseEnvelopeRequiresEveryIdentityFieldAndRejectsUnknownSemantics() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (String field : List.of("object", "id", "model", "status", "steps")) {
        var body = envelope(output("{\"recall_text\":\"tone\"}"));
        body.remove(field);
        fixture.respond(body);
        invalid(() -> models.describe(WAVE));
      }
      for (Map<String, Object> changed :
          List.<Map<String, Object>>of(
              Map.of("object", "response"),
              Map.of("id", " "),
              Map.of("id", 1),
              Map.of("created", false),
              Map.of("updated", "a\nb"),
              Map.of("usage", List.of()),
              Map.of("tool_calls", List.of()))) {
        var body = envelope(output("{\"recall_text\":\"tone\"}"));
        body.putAll(changed);
        fixture.respond(body);
        invalid(() -> models.describe(WAVE));
      }
      var valid = envelope(output("{\"recall_text\":\"tone\"}"));
      valid.put("created", "2026-10-03T00:00:00Z");
      valid.put("updated", "2026-10-03T00:00:01Z");
      valid.put("usage", Map.of("total_tokens", 3));
      fixture.respond(valid);
      assertEquals("tone", models.describe(WAVE).recallText());
    }
  }

  @Test
  void stepsMustBeBoundedAndHaveOneFinalTextOutput() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (Object steps :
          List.of(
              Map.of(),
              List.of(),
              Collections.nCopies(65, output("{}")),
              List.of(Map.of("type", "thought", "content", List.of())),
              List.of(Map.of("type", "model_output", "content", "text")),
              List.of(Map.of("type", "model_output", "content", List.of())),
              List.of(
                  Map.of("type", "model_output", "content", List.of(text("{}"), text("{}")))))) {
        var body = envelope(output("{}"));
        body.put("steps", steps);
        fixture.respond(body);
        invalid(() -> models.describe(WAVE));
      }
    }
  }

  @Test
  void textOutputRejectsOtherModalitiesAnnotationsAndNonObjectStructuredResults() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (Object content :
          List.of(
              "raw",
              Map.of("type", "audio", "text", "{}"),
              Map.of("type", "text", "text", 42),
              Map.of("type", "text", "text", "{}", "annotations", "none"),
              Map.of(
                  "type", "text", "text", "{}", "annotations", List.of(Map.of("uri", "external"))),
              Map.of("type", "text", "text", "{}", "unknown", true))) {
        fixture.respond(envelope(Map.of("type", "model_output", "content", List.of(content))));
        invalid(() -> models.describe(WAVE));
      }
      for (String result : List.of("[]", "null", "{} {}", "{\"recall_text\":12}")) {
        fixture.respond(envelope(output(result)));
        invalid(() -> models.describe(WAVE));
      }
      fixture.respond(
          envelope(
              Map.of(
                  "type",
                  "model_output",
                  "content",
                  List.of(
                      Map.of(
                          "type",
                          "text",
                          "text",
                          "{\"recall_text\":\"tone\"}",
                          "annotations",
                          List.of())))));
      assertEquals("tone", models.describe(WAVE).recallText());
    }
  }

  @Test
  void processingAndThoughtMetadataCannotHideAdditionalSemanticResults() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (List<Map<String, Object>> prefix :
          List.<List<Map<String, Object>>>of(
              List.of(Map.of("type", "thought", "summary", "text")),
              List.of(Map.of("type", "thought", "signature", "")),
              List.of(
                  Map.of("type", "processing_call", "id", "p"),
                  Map.of("type", "processing_call", "id", "p")),
              List.of(
                  Map.of("type", "processing_call", "id", "p"),
                  Map.of("type", "processing_result", "call_id", "p", "result", "fact")),
              List.of(
                  Map.of("type", "processing_call", "id", "p"),
                  Map.of("type", "processing_result", "call_id", "p", "signature", false)))) {
        var steps = new ArrayList<Object>(prefix);
        steps.add(output("{\"recall_text\":\"tone\"}"));
        var body = envelope(output("{}"));
        body.put("steps", steps);
        fixture.respond(body);
        invalid(() -> models.describe(WAVE));
      }
      var body = envelope(output("{}"));
      body.put(
          "steps",
          List.of(
              Map.of("type", "thought"),
              Map.of("type", "processing_call", "id", "p", "signature", "opaque"),
              Map.of("type", "processing_result", "call_id", "p", "signature", "opaque"),
              output("{\"recall_text\":\"tone\"}")));
      fixture.respond(body);
      assertEquals("tone", models.describe(WAVE).recallText());
    }
  }

  @Test
  void descriptionsEnforceUtf8BytesAndWellFormedUnicodeWithoutTruncation() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (String value : List.of("a".repeat(8193), "中".repeat(2731), "tone\u0085bell")) {
        fixture.structured(Map.of("recall_text", value));
        invalid(() -> models.describe(WAVE));
      }
      for (String structured :
          List.of("{\"recall_text\":\"\\udc00\"}", "{\"recall_text\":\"\\ud800x\"}")) {
        fixture.respond(envelope(output(structured)));
        invalid(() -> models.describe(WAVE));
      }
      String exact = "😀".repeat(2048);
      fixture.structured(Map.of("recall_text", exact));
      assertEquals(exact, models.describe(WAVE).recallText());
    }
  }

  @Test
  void completeDraftRequiresBoundedDistinctNonemptyClaims() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (Map<String, Object> result :
          List.<Map<String, Object>>of(
              Map.of("complete", "true", "claims", List.of("tone")),
              Map.of("complete", true, "claims", "tone"),
              Map.of("complete", true, "claims", List.of()),
              Map.of("complete", false, "claims", List.of("tone")),
              Map.of("complete", true, "claims", List.of(1)),
              Map.of("complete", true, "claims", List.of(" ")),
              Map.of("complete", true, "claims", List.of("a".repeat(1025))),
              Map.of(
                  "complete",
                  true,
                  "claims",
                  IntStream.range(0, 17).mapToObj(i -> "tone " + i).toList()),
              Map.of(
                  "complete",
                  true,
                  "claims",
                  IntStream.range(0, 3).mapToObj(i -> "中".repeat(1023) + i).toList()))) {
        fixture.structured(result);
        invalid(() -> models.draft("What happens at the end?", WAVE));
      }
      fixture.structured(Map.of("complete", true, "claims", List.of("😀".repeat(1024))));
      assertEquals(1024, models.draft("What?", WAVE).claims().getFirst().codePointCount(0, 2048));
    }
  }

  @Test
  void verifyRequiresExactCompleteBooleanAndOneIntegerSupportForEveryClaim() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (Map<String, Object> result :
          List.<Map<String, Object>>of(
              Map.of("complete", 1, "support", List.of()),
              Map.of("complete", true, "support", Map.of()),
              Map.of("complete", true, "support", List.of()),
              Map.of("complete", true, "support", List.of(Map.of("index", 0.5, "supported", true))),
              Map.of(
                  "complete",
                  true,
                  "support",
                  List.of(Map.of("index", 2147483648L, "supported", true))),
              Map.of("complete", true, "support", List.of(Map.of("index", -1, "supported", true))),
              Map.of("complete", true, "support", List.of(Map.of("index", 1, "supported", true))),
              Map.of("complete", true, "support", List.of(Map.of("index", 0, "supported", "true"))),
              Map.of(
                  "complete",
                  true,
                  "support",
                  List.of(Map.of("index", 0, "supported", true, "score", 1))))) {
        fixture.structured(result);
        invalid(() -> models.verify("What?", WAVE, List.of("tone")));
      }
      fixture.structured(
          Map.of(
              "complete",
              false,
              "support",
              List.of(
                  Map.of("index", 1, "supported", false), Map.of("index", 0, "supported", true))));
      var verification = models.verify("What?", WAVE, List.of("tone", "bell"));
      assertFalse(verification.complete());
      assertEquals(List.of(true, false), verification.supported());
    }
  }

  @Test
  void invalidQuestionAndClaimsNeverDispatchButAllowedWhitespaceIsNotRewritten() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      for (String question :
          Arrays.asList(null, " ", "x\ry", "x\u0000y", "x\u007fy", "x\ud800", "x\udfff")) {
        failure("model_invalid_input", () -> models.draft(question, WAVE));
      }
      failure("model_invalid_input", () -> models.verify("What?", WAVE, null));
      failure(
          "model_invalid_input", () -> models.verify("What?", WAVE, Arrays.asList("tone", null)));
      failure(
          "model_invalid_input",
          () -> models.verify("What?", WAVE, Collections.nCopies(17, "tone")));
      assertEquals(0, fixture.calls.get());
      String question = "  声音😀\t和末尾\n完整吗？";
      fixture.structured(Map.of("complete", false, "claims", List.of()));
      assertFalse(models.draft(question, WAVE).complete());
      var data = JSON.readTree(fixture.request.get().path("input").get(0).path("text").asString());
      assertEquals(question, data.path("question").asString());
    }
  }

  @Test
  void maximumThirtySecondWaveformIsSentWholeIncludingTheLastSample() throws Exception {
    byte[] pcm = new byte[960000];
    pcm[pcm.length - 2] = 42;
    var waveform = new AudioWaveform("b".repeat(64), "decoder-v1", 32000, 512000, pcm);
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      fixture.structured(Map.of("recall_text", "tail"));
      assertEquals("tail", models.describe(waveform).recallText());
      byte[] transmitted =
          Base64.getDecoder()
              .decode(fixture.request.get().path("input").get(1).path("data").asString());
      assertTrue(Arrays.equals(waveform.wav(), transmitted));
      assertEquals(42, transmitted[transmitted.length - 2]);
      assertEquals(1, fixture.calls.get());
    }
  }

  @Test
  void providerStatusInvalidJsonAndResponseCapFailWithoutRetryOrSecretDetails() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models()) {
      fixture.status = 429;
      fixture.body.set("private-provider-detail");
      failure("model_http_failed", () -> models.describe(WAVE));
      fixture.status = 200;
      fixture.contentType = "text/plain";
      failure("model_invalid_response", () -> models.describe(WAVE));
      fixture.contentType = "application/json";
      fixture.body.set("{bad}");
      failure("model_invalid_response", () -> models.describe(WAVE));
      fixture.body.set(" ".repeat(65537));
      failure("model_response_too_large", () -> models.describe(WAVE));
      assertEquals(4, fixture.calls.get());
    }
  }

  @Test
  void providerDeadlineCancelsTheOnlyAttempt() throws Exception {
    try (var fixture = new Fixture();
        var models = fixture.models(Duration.ofMillis(100))) {
      fixture.delayMs = 400;
      failure("model_timeout", () -> models.describe(WAVE));
      assertTrue(fixture.calls.get() <= 1);
    }
  }

  private static Map<String, Object> text(String structured) {
    return Map.of("type", "text", "text", structured);
  }

  private static Map<String, Object> output(String structured) {
    return Map.of("type", "model_output", "content", List.of(text(structured)));
  }

  private static Map<String, Object> envelope(Map<String, Object> output) {
    var body = new LinkedHashMap<String, Object>();
    body.put("object", "interaction");
    body.put("id", "fixture-interaction");
    body.put("model", "sound-fixture");
    body.put("status", "completed");
    body.put("steps", List.of(output));
    return body;
  }

  private static void invalid(Runnable call) {
    failure("model_invalid_response", call);
  }

  private static void failure(String code, Runnable call) {
    var failure = assertThrows(TextModels.Failure.class, call::run);
    assertEquals(code, failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("private-provider-detail"));
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicReference<String> body =
        new AtomicReference<>(
            JSON.writeValueAsString(envelope(output("{\"recall_text\":\"tone\"}"))));
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final HttpServer server;
    volatile int status = 200;
    volatile int delayMs;
    volatile String contentType = "application/json";

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1beta/interactions",
          exchange -> {
            try (exchange) {
              calls.incrementAndGet();
              request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
              if (delayMs > 0) {
                try {
                  Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
              byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", contentType);
              exchange.sendResponseHeaders(status, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (IOException disconnected) {
              // The deadline test closes the request before this delayed fixture writes its body.
            }
          });
      server.start();
    }

    void respond(Map<String, Object> response) {
      body.set(JSON.writeValueAsString(response));
    }

    void structured(Map<String, Object> response) {
      respond(envelope(output(JSON.writeValueAsString(response))));
    }

    GeminiSoundModels models() {
      return models(Duration.ofSeconds(5));
    }

    GeminiSoundModels models(Duration deadline) {
      return new GeminiSoundModels(
          new GeminiSoundModels.Configuration(
              new Endpoint(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                  "sound-fixture",
                  "fixture-key"),
              "pinned-v1",
              deadline,
              65536,
              true));
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
