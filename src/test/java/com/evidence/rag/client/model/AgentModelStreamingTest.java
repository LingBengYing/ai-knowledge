package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentModelDelta;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentModelStreamingTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void reasoningAndAnswerArriveBeforeToolTurnFinishesAcrossUtf8AndJsonEscapeBoundaries()
      throws Exception {
    var finish = new CountDownLatch(1);
    var firstReasoning = new CountDownLatch(1);
    var firstAnswer = new CountDownLatch(1);
    var deltas = new CopyOnWriteArrayList<AgentModelDelta>();
    String begin = "{\"result\":{\"refused\":false,\"statements\":[{\"text\":\"第一句";
    String tail =
        "\\n\\\"引号\\\"\\uD83C\\uDF32\",\"evidence_ids\":[\"source-1\"]},"
            + "{\"evidence_ids\":[\"source-1\"],\"text\":\"第二句\"}],\"suggestions\":[]}}";
    try (var server =
            new Server(
                exchange -> {
                  write(
                      exchange,
                      frame(Map.of("role", "assistant", "reasoning_content", "合成思考🌲")),
                      true);
                  write(
                      exchange,
                      frame(Map.of("tool_calls", List.of(tool(0, "call-one", "terminate", begin)))),
                      false);
                  await(finish);
                  for (int i = 0; i < tail.length(); i++) {
                    write(
                        exchange,
                        frame(Map.of("tool_calls", List.of(argument(0, tail.substring(i, i + 1))))),
                        false);
                  }
                  write(
                      exchange,
                      terminal("tool_calls")
                          + "data: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n",
                      false);
                });
        var models = models(server, Duration.ofSeconds(5), 1024 * 1024);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          executor.submit(
              () ->
                  models.agentChat(
                      List.of(new AgentMessage("user", "fixture")),
                      delta -> {
                        deltas.add(delta);
                        if (!delta.reasoningDelta().isEmpty()) {
                          firstReasoning.countDown();
                        }
                        if (!delta.answerDelta().isEmpty()) {
                          firstAnswer.countDown();
                        }
                      }));
      try {
        assertTrue(firstReasoning.await(2, TimeUnit.SECONDS));
        assertTrue(firstAnswer.await(2, TimeUnit.SECONDS));
        assertFalse(future.isDone(), "Model turn is still open while both fields are visible");
        assertEquals("第一句", answer(deltas));
      } finally {
        finish.countDown();
      }
      var result = future.get(3, TimeUnit.SECONDS);
      assertEquals(begin + tail, result.toolCalls().getFirst().function().arguments());
      assertEquals("合成思考🌲", result.reasoningContent());
      assertEquals("第一句\n\"引号\"🌲\n\n第二句", answer(deltas));
      assertEquals(
          "合成思考🌲",
          deltas.stream().map(AgentModelDelta::reasoningDelta).reduce("", String::concat));
      assertTrue(server.request.get().path("stream").asBoolean());
      assertEquals(1, server.calls.get());
      assertFalse(deltas.toString().contains("第一句"));
    }
  }

  @Test
  void interleavedToolIndexesAccumulateIndependentlyWithoutLeakingArgumentJson() throws Exception {
    var deltas = new ArrayList<AgentModelDelta>();
    try (var server =
            new Server(
                exchange -> {
                  write(
                      exchange,
                      frame(
                          Map.of(
                              "role",
                              "assistant",
                              "tool_calls",
                              List.of(
                                  tool(0, "call-one", "knowledge_search", "{\"query\":\"一"),
                                  tool(1, "call-two", "knowledge_search", "{\"query\":\"二")))),
                      false);
                  write(
                      exchange,
                      frame(
                          Map.of("tool_calls", List.of(argument(1, "号\"}"), argument(0, "号\"}")))),
                      false);
                  write(exchange, terminal("tool_calls") + "data: [DONE]\n\n", false);
                });
        var models = models(server, Duration.ofSeconds(2), 1024 * 1024)) {
      var result = models.agentChat(List.of(new AgentMessage("user", "fixture")), deltas::add);
      assertEquals("{\"query\":\"一号\"}", result.toolCalls().get(0).function().arguments());
      assertEquals("{\"query\":\"二号\"}", result.toolCalls().get(1).function().arguments());
      assertTrue(deltas.isEmpty());
    }
  }

  @Test
  void incompleteMalformedAndNonToolStreamsFailWithoutRetryOrReturningAnAction() throws Exception {
    String start =
        frame(
            Map.of(
                "role",
                "assistant",
                "tool_calls",
                List.of(tool(0, "call-one", "knowledge_search", "{\"query\":\"fixture\"}"))));
    for (String body :
        List.of(
            start,
            start + terminal("tool_calls"),
            start + "data: [DONE]\n\n",
            start + terminal("length") + "data: [DONE]\n\n",
            start + terminal("tool_calls") + "data: [DONE]\n\ndata: {}\n\n",
            "data: {\"choices\":[],\"error\":{\"private\":\"hidden\"}}\n\n",
            frame(Map.of("role", "assistant", "content", "not an authoritative answer"))
                + terminal("stop")
                + "data: [DONE]\n\n")) {
      try (var server = new Server(exchange -> write(exchange, body, false));
          var models = models(server, Duration.ofSeconds(2), 1024 * 1024)) {
        var failure =
            assertThrows(
                TextModels.Failure.class,
                () -> models.agentChat(List.of(new AgentMessage("user", "fixture")), delta -> {}));
        assertTrue(
            List.of("model_invalid_response", "model_tool_required").contains(failure.code()));
        assertEquals(1, server.calls.get());
        assertFalse(failure.toString().contains("hidden"));
      }
    }
  }

  @Test
  void timeoutAndResponseByteLimitAlsoApplyWhileReceivingStreamingDeltas() throws Exception {
    var release = new CountDownLatch(1);
    try (var server =
            new Server(
                exchange -> {
                  write(
                      exchange,
                      frame(Map.of("role", "assistant", "reasoning_content", "first")),
                      false);
                  await(release);
                });
        var models = models(server, Duration.ofMillis(150), 1024 * 1024)) {
      try {
        assertEquals(
            "model_timeout",
            assertThrows(
                    TextModels.Failure.class,
                    () ->
                        models.agentChat(List.of(new AgentMessage("user", "fixture")), delta -> {}))
                .code());
      } finally {
        release.countDown();
      }
      assertEquals(1, server.calls.get());
    }
    try (var server =
            new Server(
                exchange ->
                    write(
                        exchange,
                        frame(Map.of("role", "assistant", "reasoning_content", "x".repeat(1000))),
                        false));
        var models = models(server, Duration.ofSeconds(2), 128)) {
      assertEquals(
          "model_response_too_large",
          assertThrows(
                  TextModels.Failure.class,
                  () -> models.agentChat(List.of(new AgentMessage("user", "fixture")), delta -> {}))
              .code());
      assertEquals(1, server.calls.get());
    }
  }

  @Test
  void displayExtractionIgnoresSimilarKeysAndPreservesSplitEscapes() {
    var values = new ArrayList<String>();
    var preview = new AgentAnswerPreview(values::add);
    String input =
        "{\"result\":{\"suggestions\":[{\"text\":\"not answer\"}],\"statements\":["
            + "{\"other\":{\"text\":\"not answer\"},\"text\":\"A\\n\\uD83C\\uDF32\\\\B\"}]}}";
    for (int i = 0; i < input.length(); i++) {
      preview.accept(input.substring(i, i + 1));
    }
    assertEquals("A\n🌲\\B", String.join("", values));
    var bad = new AgentAnswerPreview(value -> {});
    assertThrows(
        TextModels.Failure.class,
        () -> bad.accept("{\"result\":{\"statements\":[{\"text\":\"\\uD800x"));
  }

  @Test
  void cancellingAnOpenStreamStopsTheTransportWithoutDispatchingAnotherRequest() throws Exception {
    var release = new CountDownLatch(1);
    var sawFirst = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var code = new AtomicReference<String>();
    try (var server =
            new Server(
                exchange -> {
                  write(
                      exchange,
                      frame(Map.of("role", "assistant", "reasoning_content", "first")),
                      false);
                  await(release);
                });
        var models = models(server, Duration.ofSeconds(5), 1024 * 1024);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          executor.submit(
              () -> {
                try {
                  models.agentChat(
                      List.of(new AgentMessage("user", "fixture")), delta -> sawFirst.countDown());
                } catch (TextModels.Failure failure) {
                  code.set(failure.code());
                } finally {
                  stopped.countDown();
                }
              });
      try {
        assertTrue(sawFirst.await(2, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals("model_interrupted", code.get());
        assertEquals(1, server.calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void reasoningSurrogatesWaitForTheirPairWithoutChangingTheNativeHistory() {
    var deltas = new ArrayList<AgentModelDelta>();
    var stream = new AgentModelStream(deltas::add);
    stream.accept(
        "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"\\uD83C\"}}]}");
    assertTrue(deltas.isEmpty());
    stream.accept(
        "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"\\uDF32\",\"tool_calls\":[{\"index\":0,\"id\":\"call-one\",\"type\":\"function\",\"function\":{\"name\":\"knowledge_search\",\"arguments\":\"{\\\"query\\\":\\\"fixture\\\"}\"}}]}}]}");
    stream.accept("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
    stream.accept("[DONE]");
    assertEquals("🌲", stream.finish().reasoningContent());
    assertEquals(List.of(new AgentModelDelta("🌲", "")), deltas);
  }

  private static String answer(List<AgentModelDelta> values) {
    return values.stream().map(AgentModelDelta::answerDelta).reduce("", String::concat);
  }

  private static Map<String, Object> tool(int index, String id, String name, String arguments) {
    return Map.of(
        "index",
        index,
        "id",
        id,
        "type",
        "function",
        "function",
        Map.of("name", name, "arguments", arguments));
  }

  private static Map<String, Object> argument(int index, String arguments) {
    return Map.of("index", index, "function", Map.of("arguments", arguments));
  }

  private static String frame(Map<String, Object> delta) {
    return "data: "
        + JSON.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0, "delta", delta))))
        + "\n\n";
  }

  private static String terminal(String reason) {
    return "data: "
        + JSON.writeValueAsString(
            Map.of(
                "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", reason))))
        + "\n\n";
  }

  private static void write(HttpExchange exchange, String value, boolean splitBytes)
      throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (splitBytes) {
      for (byte next : bytes) {
        exchange.getResponseBody().write(next);
        exchange.getResponseBody().flush();
      }
    } else {
      exchange.getResponseBody().write(bytes);
      exchange.getResponseBody().flush();
    }
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(4, TimeUnit.SECONDS)) {
        throw new IOException("synthetic stream gate timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("synthetic stream interrupted");
    }
  }

  private static OpenAiCompatibleModels models(Server server, Duration timeout, int responseBytes) {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(server.uri(), "synthetic", "local-fixture-only");
    return new OpenAiCompatibleModels(
        new OpenAiCompatibleModels.Configuration(
            endpoint, endpoint, endpoint, 2, timeout, responseBytes, true));
  }

  @FunctionalInterface
  private interface Reply {
    void respond(HttpExchange exchange) throws IOException;
  }

  private static final class Server implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();

    Server(Reply reply) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              calls.incrementAndGet();
              request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
              exchange.sendResponseHeaders(200, 0);
              reply.respond(exchange);
            }
          });
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
      executor.close();
    }
  }
}
