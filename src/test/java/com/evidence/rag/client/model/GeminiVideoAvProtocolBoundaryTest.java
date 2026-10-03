package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class GeminiVideoAvProtocolBoundaryTest {
  private static final String ID = "c".repeat(64);
  private static final String OTHER_ID = "d".repeat(64);
  private static final String CLAIM = "The bell moves and rings.";

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidEnvelopes")
  void incompleteOrSemanticEnvelopeCannotBecomeAVideoFact(String reason, String response)
      throws Exception {
    try (var f = new Wire()) {
      f.response.set(response);
      failure("model_invalid_response", () -> draft(f.models));
      assertEquals(1, f.calls.get(), reason);
    }
  }

  static Stream<Arguments> invalidEnvelopes() {
    var cases = new ArrayList<Arguments>();
    var malformedFields = new LinkedHashMap<String, Object>();
    malformedFields.put("id", "");
    malformedFields.put("created", 7);
    malformedFields.put("updated", " ");
    malformedFields.put("object", "other");
    malformedFields.put("usage", List.of());
    malformedFields.put("steps", Map.of());
    malformedFields.put("model", 7);
    for (var entry : malformedFields.entrySet()) {
      var envelope = envelope(List.of(output(claims(true, List.of(claim(CLAIM))))));
      envelope.put(entry.getKey(), entry.getValue());
      cases.add(Arguments.of("wrong " + entry.getKey(), json(envelope)));
    }
    for (String value : List.of("x".repeat(4097), "中".repeat(1366), "bad\u0001metadata")) {
      var envelope = envelope(List.of(output(claims(true, List.of(claim(CLAIM))))));
      envelope.put("id", value);
      cases.add(Arguments.of("unbounded or control metadata", json(envelope)));
    }
    var missing = envelope(List.of(output(claims(true, List.of(claim(CLAIM))))));
    missing.remove("status");
    cases.add(Arguments.of("missing status", json(missing)));
    for (List<?> steps :
        List.of(
            List.of(),
            Collections.nCopies(65, output("{}")),
            List.of(Map.of("type", "model_output", "content", Map.of())),
            List.of(Map.of("type", "model_output", "content", List.of())),
            List.of(Map.of("type", "model_output", "content", List.of(text("{}"), text("{}")))),
            List.of(Map.of("type", "function_result", "content", List.of(text("{}")))),
            List.of(
                Map.of(
                    "type",
                    "model_output",
                    "content",
                    List.of(Map.of("type", "image", "text", "{}")))),
            List.of(
                Map.of(
                    "type",
                    "model_output",
                    "content",
                    List.of(Map.of("type", "text", "text", "{}", "annotations", Map.of())))),
            List.of(
                Map.of(
                    "type",
                    "model_output",
                    "content",
                    List.of(
                        Map.of("type", "text", "text", "{}", "annotations", List.of("citation"))))),
            List.of(
                Map.of(
                    "type",
                    "model_output",
                    "content",
                    List.of(Map.of("type", "text", "text", 42)))))) {
      cases.add(Arguments.of("invalid final output shape", json(envelope(steps))));
    }
    for (String escaped : List.of("\\ud800", "\\ud800x", "\\udc00")) {
      cases.add(
          Arguments.of(
              "broken metadata Unicode",
              json(envelope(List.of(output("{}")))).replace("fixture-id", escaped)));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidClaims")
  void draftMustContainTheWholeBoundedDistinctTypedFactSet(String reason, String result)
      throws Exception {
    try (var f = new Wire()) {
      f.response.set(json(envelope(List.of(output(result)))));
      failure("model_invalid_response", () -> draft(f.models));
      assertEquals(1, f.calls.get(), reason);
    }
  }

  static Stream<Arguments> invalidClaims() {
    var cases = new ArrayList<Arguments>();
    cases.add(Arguments.of("complete cannot be numeric", "{\"complete\":1,\"claims\":[]}"));
    cases.add(Arguments.of("claims cannot be object", "{\"complete\":true,\"claims\":{}}"));
    cases.add(Arguments.of("complete needs a fact", claims(true, List.of())));
    cases.add(
        Arguments.of(
            "incomplete must not retain a partial fact", claims(false, List.of(claim(CLAIM)))));
    cases.add(
        Arguments.of(
            "invalid requirement",
            "{\"complete\":true,\"claims\":[{\"text\":\"Bell\",\"requirement\":\"visual\"}]}"));
    cases.add(Arguments.of("claim is not an object", claims(true, List.of("Bell"))));
    cases.add(Arguments.of("duplicate fact", claims(true, List.of(claim(CLAIM), claim(CLAIM)))));
    cases.add(
        Arguments.of(
            "over sixteen facts",
            claims(true, IntStream.range(0, 17).mapToObj(i -> claim("Fact " + i)).toList())));
    cases.add(
        Arguments.of(
            "aggregate Unicode bytes",
            claims(
                true,
                List.of(
                    claim("甲".repeat(1000)), claim("乙".repeat(1000)), claim("丙".repeat(1000))))));
    for (String value :
        List.of(" ", "x".repeat(1025), "x".repeat(8193), "bad\u007ffact", "bad\u0001fact")) {
      cases.add(Arguments.of("invalid full claim text", claims(true, List.of(claim(value)))));
    }
    for (String escaped : List.of("\\ud800", "\\ud800x", "\\udc00")) {
      cases.add(
          Arguments.of(
              "broken fact Unicode",
              "{\"complete\":true,\"claims\":[{\"text\":\""
                  + escaped
                  + "\",\"requirement\":\"JOINT\"}]}"));
    }
    return cases.stream();
  }

  @Test
  void pairedMediaProcessingRejectsDuplicateOrSemanticStepsButAllowsOpaqueMetadata()
      throws Exception {
    try (var f = new Wire()) {
      var finalOutput = output(claims(true, List.of(claim("A 🔔 moves and rings."))));
      for (List<?> prefix :
          List.of(
              List.of(
                  Map.of("type", "processing_call", "id", "p"),
                  Map.of("type", "processing_call", "id", "p")),
              List.of(
                  Map.of("type", "processing_call", "id", "p"),
                  Map.of("type", "processing_result", "call_id", "p"),
                  Map.of("type", "processing_result", "call_id", "p")),
              List.of(Map.of("type", "thought", "summary", Map.of())),
              List.of(Map.of("type", "thought", "signature", "")),
              List.of(Map.of("type", "processing_call", "id", "p", "description", "claim")))) {
        var steps = new ArrayList<Object>(prefix);
        steps.add(finalOutput);
        f.response.set(json(envelope(steps)));
        failure("model_invalid_response", () -> draft(f.models));
      }
      var valid =
          envelope(
              List.of(
                  Map.of("type", "thought", "signature", "opaque🔔", "summary", List.of()),
                  Map.of("type", "processing_call", "id", "p", "signature", "opaque"),
                  Map.of("type", "processing_result", "call_id", "p", "signature", "opaque"),
                  Map.of(
                      "type",
                      "model_output",
                      "content",
                      List.of(
                          Map.of(
                              "type",
                              "text",
                              "text",
                              claims(true, List.of(claim("A 🔔 moves and rings."))),
                              "annotations",
                              List.of())))));
      valid.put("created", "2026-10-03T00:00:00Z");
      valid.put("updated", "2026-10-03T00:00:01Z");
      f.response.set(json(valid));
      assertEquals("A 🔔 moves and rings.", draft(f.models).claims().getFirst().text());
      assertEquals(6, f.calls.get());
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSupports")
  void verifierCannotReturnAnUntypedIncompleteOrRepeatedReceipt(String reason, String result)
      throws Exception {
    try (var f = new Wire()) {
      f.response.set(json(envelope(List.of(output(result)))));
      failure("model_invalid_response", () -> verify(f.models, facts()));
      assertEquals(1, f.calls.get(), reason);
    }
  }

  static Stream<Arguments> invalidSupports() {
    var cases = new ArrayList<Arguments>();
    cases.add(Arguments.of("numeric complete", "{\"complete\":1,\"support\":[]}"));
    cases.add(Arguments.of("nonarray support", "{\"complete\":true,\"support\":{}}"));
    cases.add(
        Arguments.of(
            "partial support", json(Map.of("complete", true, "support", List.of(support(ID))))));
    cases.add(
        Arguments.of(
            "duplicate id",
            json(Map.of("complete", true, "support", List.of(support(ID), support(ID))))));
    for (String field : List.of("supported", "visual_contribution", "audio_contribution")) {
      var invalid = new LinkedHashMap<>(support(ID));
      invalid.put(field, "true");
      cases.add(
          Arguments.of(
              "untyped " + field,
              json(Map.of("complete", true, "support", List.of(invalid, support(OTHER_ID))))));
    }
    return cases.stream();
  }

  @Test
  void reorderedNegativeSupportsRemainBoundToServerFactsAndTransmitOriginalCandidates()
      throws Exception {
    try (var f = new Wire()) {
      var rejected = new LinkedHashMap<>(support(ID));
      rejected.put("supported", false);
      rejected.put("audio_contribution", false);
      f.response.set(
          json(
              envelope(
                  List.of(
                      output(
                          json(
                              Map.of(
                                  "complete",
                                  false,
                                  "support",
                                  List.of(support(OTHER_ID), rejected))))))));
      var result = verify(f.models, facts());
      assertFalse(result.complete());
      assertEquals(
          List.of(ID, OTHER_ID), result.support().stream().map(VideoAvModels.Support::id).toList());
      assertFalse(result.support().getFirst().supported());
      var text = f.request.get().path("input").get(2).path("text").asString();
      var requestFacts = VideoAvClientFixture.JSON.readTree(text).path("claims");
      assertEquals(ID, requestFacts.get(0).path("id").asString());
      assertEquals(CLAIM, requestFacts.get(0).path("text").asString());
      assertEquals("JOINT", requestFacts.get(0).path("requirement").asString());
      assertEquals(1, f.calls.get());
    }
  }

  @Test
  void invalidFactSetsAndAbsentVisualMaterialStopBeforeProviderDispatch() throws Exception {
    try (var f = new Wire()) {
      var first = facts().getFirst();
      var duplicateId =
          new VideoAvFact(ID, "A second observation.", VideoAvRequirement.JOINT, false, false);
      for (List<VideoAvFact> invalid :
          Arrays.<List<VideoAvFact>>asList(
              null,
              List.of(),
              Arrays.asList(first, null),
              Collections.nCopies(17, first),
              List.of(first, duplicateId),
              List.of(
                  first,
                  new VideoAvFact(OTHER_ID, CLAIM, VideoAvRequirement.JOINT, false, false)))) {
        failure("model_invalid_input", () -> verify(f.models, invalid));
      }
      failure(
          "model_invalid_input",
          () ->
              f.models.verify(
                  "Question?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.VISUAL,
                  facts()));
      var actual = VideoAvClientFixture.window();
      var audioOnly =
          new VideoAvWindow(
              actual.id(),
              actual.ordinal(),
              actual.startTick(),
              actual.endTick(),
              null,
              actual.audio());
      failure(
          "model_invalid_input",
          () ->
              f.models.draft(
                  "Question?", audioOnly, VideoAvClientFixture.EPOCH, VideoAvMode.VISUAL));
      assertEquals(0, f.calls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "  ", "question\u0000", "question\u007f", "question\ud800", "question\udc00"})
  void unsupportedQuestionCharactersAreRejectedBeforeEitherProtocol(String question)
      throws Exception {
    try (var f = new Wire();
        var embeddings = f.embeddings()) {
      failure(
          "model_invalid_input",
          () ->
              f.models.draft(
                  question,
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT));
      failure("model_invalid_input", () -> embeddings.embedText(question));
      assertEquals(0, f.calls.get());
    }
  }

  @Test
  void completeQuestionUtf8BoundaryKeepsTabsNewlinesAndSupplementaryCharacters() throws Exception {
    try (var f = new Wire()) {
      String question = "x".repeat(4090) + "\n\t🔔";
      assertEquals(4096, question.getBytes(StandardCharsets.UTF_8).length);
      draft(f.models, question);
      var input = f.request.get().path("input").get(2).path("text").asString();
      assertEquals(question, VideoAvClientFixture.JSON.readTree(input).path("question").asString());
      failure("model_invalid_input", () -> draft(f.models, question + "x"));
      assertEquals(1, f.calls.get());
    }
  }

  @Test
  void httpFailureAndOversizedResponseNeverRetryOrExposeProviderBody() throws Exception {
    try (var f = new Wire()) {
      f.httpStatus.set(503);
      f.response.set("provider-internal-message");
      var failed = assertThrows(TextModels.Failure.class, () -> draft(f.models));
      assertEquals("model_http_failed", failed.code());
      assertFalse(failed.toString().contains("provider-internal-message"));
      f.httpStatus.set(200);
      f.response.set(" ".repeat(65537));
      failure("model_response_too_large", () -> draft(f.models));
      assertEquals(2, f.calls.get());
    }
  }

  @Test
  void embeddingRejectsMissingAndNonarrayValuesAndCanonicalizesFiniteCoordinates()
      throws Exception {
    try (var f = new Wire();
        var embedding = f.embeddings()) {
      for (String invalid :
          List.of(
              "{}",
              "{\"embedding\":[]}",
              "{\"embedding\":{\"values\":{}}}",
              "{\"embedding\":{\"values\":[1,2,1e400]}}")) {
        f.response.set(invalid);
        failure("model_invalid_response", () -> embedding.embedText("What moves?"));
      }
      f.response.set(
          "{\"embedding\":{\"values\":[1e-100,-0.0,0.1]},\"usageMetadata\":{\"totalTokenCount\":10}}");
      assertEquals(List.of(0.0, 0.0, (double) 0.1f), embedding.embedText("What moves?"));
      Thread.currentThread().interrupt();
      try {
        failure("model_interrupted", () -> embedding.embedAudio(VideoAvClientFixture.audio()));
      } finally {
        assertTrue(Thread.interrupted());
      }
      assertEquals(5, f.calls.get());
    }
  }

  private static List<VideoAvFact> facts() {
    return List.of(
        new VideoAvFact(ID, CLAIM, VideoAvRequirement.JOINT, false, false),
        new VideoAvFact(OTHER_ID, "The room is bright.", VideoAvRequirement.VISUAL, false, false));
  }

  private static VideoAvModels.Draft draft(GeminiVideoAvModels models) {
    return draft(models, "Does the bell move and ring?");
  }

  private static VideoAvModels.Draft draft(GeminiVideoAvModels models, String question) {
    return models.draft(
        question, VideoAvClientFixture.window(), VideoAvClientFixture.EPOCH, VideoAvMode.JOINT);
  }

  private static VideoAvModels.Verification verify(
      GeminiVideoAvModels models, List<VideoAvFact> facts) {
    return models.verify(
        "Does the bell move and ring, and is the room bright?",
        VideoAvClientFixture.window(),
        VideoAvClientFixture.EPOCH,
        VideoAvMode.JOINT,
        facts);
  }

  private static Map<String, Object> claim(String text) {
    return Map.of("text", text, "requirement", "JOINT");
  }

  private static String claims(boolean complete, List<?> claims) {
    return json(Map.of("complete", complete, "claims", claims));
  }

  private static Map<String, Object> support(String id) {
    return Map.of(
        "id", id, "supported", true, "visual_contribution", true, "audio_contribution", true);
  }

  private static Map<String, Object> text(String text) {
    return Map.of("type", "text", "text", text);
  }

  private static Map<String, Object> output(String result) {
    return Map.of("type", "model_output", "content", List.of(text(result)));
  }

  private static Map<String, Object> envelope(List<?> steps) {
    return new LinkedHashMap<>(
        Map.of("id", "fixture-id", "model", "av-fixture", "status", "completed", "steps", steps));
  }

  private static String json(Object value) {
    return VideoAvClientFixture.JSON.writeValueAsString(value);
  }

  private static void failure(String code, Runnable operation) {
    assertEquals(code, assertThrows(TextModels.Failure.class, operation::run).code());
  }

  private static final class Wire implements AutoCloseable {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger httpStatus = new AtomicInteger(200);
    private final AtomicReference<String> response =
        new AtomicReference<>(json(envelope(List.of(output(claims(true, List.of(claim(CLAIM))))))));
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final HttpServer server;
    private final Endpoint endpoint;
    private final GeminiVideoAvModels models;

    Wire() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              calls.incrementAndGet();
              request.set(
                  VideoAvClientFixture.JSON.readTree(exchange.getRequestBody().readAllBytes()));
              byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(httpStatus.get(), bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
      endpoint =
          new Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "av-fixture",
              "synthetic-key");
      models =
          new GeminiVideoAvModels(
              new GeminiVideoAvModels.Configuration(
                  endpoint, "fixture-v1", Duration.ofSeconds(3), 65536, true));
    }

    GeminiVideoAvEmbeddingModels embeddings() {
      return new GeminiVideoAvEmbeddingModels(
          new GeminiVideoAvEmbeddingModels.Configuration(
              endpoint,
              "fixture-v1",
              3,
              VideoAvClientFixture.DECODER,
              Duration.ofSeconds(3),
              65536,
              true));
    }

    @Override
    public void close() {
      models.close();
      server.stop(0);
    }
  }
}
