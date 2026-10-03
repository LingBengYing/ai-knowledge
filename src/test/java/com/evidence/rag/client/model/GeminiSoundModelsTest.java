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
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiSoundModelsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AudioWaveform WAVE =
      new AudioWaveform("a".repeat(64), "decoder-v1", 8, 12, new byte[] {1, 0, 0, 0, 0, 0, 3, 0});

  @Test
  void threeStagesEachSendCompleteOriginalWaveAndIndependentStrictJsonRequest() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(0, f.calls.get());
      assertEquals(f.config.revision(), f.models.revision());
      f.result.set("{\"recall_text\":\"\"}");
      assertEquals("", f.models.describe(WAVE).recallText());
      f.assertWire();
      f.result.set("{\"complete\":true,\"claims\":[\"A bell rings.\"]}");
      assertEquals(List.of("A bell rings."), f.models.draft("What can be heard?", WAVE).claims());
      f.assertWire();
      assertEquals(
          "What can be heard?",
          JSON.readTree(f.request.get().path("input").get(0).path("text").asString())
              .path("question")
              .asString());
      f.result.set("{\"complete\":true,\"support\":[{\"index\":0,\"supported\":true}]}");
      assertEquals(
          List.of(true),
          f.models.verify("What can be heard?", WAVE, List.of("A bell rings.")).supported());
      f.assertWire();
      assertEquals(3, f.calls.get());
    }
  }

  @Test
  void acceptsOnlyPairedProcessingAndEmptyThoughtBeforeTheUniqueOutput() throws Exception {
    try (var f = new Fixture()) {
      f.prefix.set(
          "{\"type\":\"thought\",\"signature\":\"opaque\",\"summary\":[]},{\"type\":\"processing_call\",\"id\":\"p1\"},{\"type\":\"processing_result\",\"call_id\":\"p1\"},");
      assertEquals("bell", f.models.describe(WAVE).recallText());
      for (String prefix :
          List.of(
              "{\"type\":\"function_call\",\"name\":\"tool\"},",
              "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"facts\"}]},",
              "{\"type\":\"processing_call\",\"id\":\"p1\"},",
              "{\"type\":\"processing_result\",\"call_id\":\"p1\"},",
              "{\"type\":\"processing_call\",\"id\":\"p1\",\"text\":\"facts\"},",
              "{\"type\":\"user_input\",\"content\":[]},")) {
        f.prefix.set(prefix);
        failure("model_invalid_response", () -> f.models.describe(WAVE));
      }
    }
  }

  @Test
  void rejectsUnfinishedMismatchedAndAmbiguousOutputWithoutRetry() throws Exception {
    try (var f = new Fixture()) {
      for (String status :
          List.of(
              "incomplete", "requires_action", "failed", "cancelled", "queued", "in_progress")) {
        f.status.set(status);
        failure("model_invalid_response", () -> f.models.describe(WAVE));
      }
      f.status.set("completed");
      f.model.set("other-model");
      failure("model_invalid_response", () -> f.models.describe(WAVE));
      f.model.set("sound-fixture");
      f.prefix.set(
          "{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]},");
      failure("model_invalid_response", () -> f.models.describe(WAVE));
      assertEquals(8, f.calls.get());
    }
  }

  @Test
  void strictFactsRejectDuplicatesUnknownFieldsPartialSupportAndInvalidUnicode() throws Exception {
    try (var f = new Fixture()) {
      for (String result :
          List.of(
              "{\"recall_text\":\"bell\",\"time\":1}",
              "{\"recall_text\":\"bell\",\"recall_text\":\"horn\"}",
              "{\"recall_text\":\"\\ud800\"}",
              "{\"recall_text\":\"a\\u0000b\"}")) {
        f.result.set(result);
        failure("model_invalid_response", () -> f.models.describe(WAVE));
      }
      f.result.set("{\"complete\":true,\"claims\":[\"bell\",\"bell\"]}");
      failure("model_invalid_response", () -> f.models.draft("What?", WAVE));
      f.result.set(
          "{\"complete\":true,\"support\":[{\"index\":0,\"supported\":true},{\"index\":0,\"supported\":true}]}");
      failure(
          "model_invalid_response", () -> f.models.verify("What?", WAVE, List.of("bell", "horn")));
      f.result.set("{\"complete\":false,\"claims\":[]}");
      assertFalse(f.models.draft("What?", WAVE).complete());
    }
  }

  @Test
  void rejectsOversizedQuestionsAndClaimsBeforeNetworkAndRetainsClosedFailure() throws Exception {
    try (var f = new Fixture()) {
      failure("model_invalid_input", () -> f.models.draft("中".repeat(1366), WAVE));
      failure("model_invalid_input", () -> f.models.verify("What?", WAVE, List.of()));
      failure("model_invalid_input", () -> f.models.describe(null));
      assertEquals(0, f.calls.get());
      f.models.close();
      failure("model_closed", () -> f.models.describe(WAVE));
    }
  }

  @Test
  void configurationRevisionExcludesSecretsAndBindsExplicitVersionOffline() {
    var endpoint = new Endpoint(URI.create("https://sound.invalid"), "explicit-model", "key-one");
    var a =
        new GeminiSoundModels.Configuration(
            endpoint, "pinned-v1", Duration.ofSeconds(3), 65536, false);
    var b =
        new GeminiSoundModels.Configuration(
            new Endpoint(endpoint.baseUrl(), endpoint.model(), "key-two"),
            "pinned-v1",
            Duration.ofSeconds(8),
            131072,
            false);
    assertEquals(a.revision(), b.revision());
    assertNotEquals(
        a.revision(),
        new GeminiSoundModels.Configuration(
                endpoint, "pinned-v2", Duration.ofSeconds(3), 65536, false)
            .revision());
    assertEquals("Configuration[redacted]", a.toString());
    failure(
        "model_invalid_configuration",
        () ->
            new GeminiSoundModels.Configuration(
                endpoint, "latest", Duration.ofSeconds(3), 65536, false));
  }

  private static void failure(String code, Runnable call) {
    var ex = assertThrows(TextModels.Failure.class, call::run);
    assertEquals(code, ex.code());
    assertNull(ex.getCause());
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicReference<String> result = new AtomicReference<>("{\"recall_text\":\"bell\"}");
    final AtomicReference<String> prefix = new AtomicReference<>("");
    final AtomicReference<String> status = new AtomicReference<>("completed");
    final AtomicReference<String> model = new AtomicReference<>("sound-fixture");
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final HttpServer server;
    final GeminiSoundModels.Configuration config;
    final GeminiSoundModels models;

    Fixture() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              calls.incrementAndGet();
              assertEquals("/v1beta/interactions", exchange.getRequestURI().toString());
              assertEquals(
                  "synthetic-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
              assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
              request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
              var output =
                  JSON.writeValueAsString(
                      Map.of(
                          "type",
                          "model_output",
                          "content",
                          List.of(Map.of("type", "text", "text", result.get()))));
              var envelope =
                  "{\"object\":\"interaction\",\"id\":\"i1\",\"model\":\""
                      + model.get()
                      + "\",\"status\":\""
                      + status.get()
                      + "\",\"steps\":["
                      + prefix.get()
                      + output
                      + "],\"usage\":{\"total_tokens\":3}}";
              byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
      config =
          new GeminiSoundModels.Configuration(
              new Endpoint(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                  "sound-fixture",
                  "synthetic-key"),
              "pinned-v1",
              Duration.ofSeconds(3),
              65536,
              true);
      models = new GeminiSoundModels(config);
    }

    void assertWire() {
      var body = request.get();
      assertEquals(
          Set.of(
              "model",
              "store",
              "stream",
              "background",
              "input",
              "system_instruction",
              "response_format",
              "generation_config"),
          new HashSet<>(body.propertyNames()));
      assertFalse(body.path("store").asBoolean());
      assertFalse(body.path("stream").asBoolean());
      assertFalse(body.path("background").asBoolean());
      assertEquals(2, body.path("input").size());
      assertEquals(
          Base64.getEncoder().encodeToString(WAVE.wav()),
          body.path("input").get(1).path("data").asString());
      assertEquals("audio/wav", body.path("input").get(1).path("mime_type").asString());
      assertEquals("application/json", body.path("response_format").path("mime_type").asString());
      assertFalse(
          body.path("response_format").path("schema").path("additionalProperties").asBoolean());
      assertTrue(body.path("response_format").path("schema").has("required"));
    }

    @Override
    public void close() {
      models.close();
      server.stop(0);
    }
  }
}
