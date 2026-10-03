package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.domain.TextModelRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TextModelConnectionProbeTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();
  private HttpServer server;
  private URI base;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  @Test
  void threeRoleTestsEachSendExactlyOneSyntheticProductionProtocolRequest() {
    var probe = probe(null);
    assertEquals(0, requests.size());
    response.set(
        "{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":1,\"embedding\":[0,1]}]}");
    assertNull(probe.test(configuration(), TextModelRole.EMBEDDING));
    response.set(
        "{\"results\":[{\"index\":1,\"relevance_score\":0.1},{\"index\":0,\"relevance_score\":0.9}]}");
    assertNull(probe.test(configuration(), TextModelRole.RERANK));
    response.set(generation("合成资料中的核验词是青松。"));
    assertNull(probe.test(configuration(), TextModelRole.GENERATION));
    assertEquals(
        List.of("/v1/embeddings", "/v1/rerank", "/v1/chat/completions"),
        requests.stream().map(Request::path).toList());
    assertEquals(
        List.of(
            "Bearer synthetic-embedding", "Bearer synthetic-rerank", "Bearer synthetic-generation"),
        requests.stream().map(Request::authorization).toList());
    assertEquals(2, requests.getFirst().body().path("input").size());
    assertEquals("float", requests.getFirst().body().path("encoding_format").stringValue());
    assertEquals(2, requests.get(1).body().path("documents").size());
    assertFalse(requests.get(2).body().path("stream").booleanValue());
    assertEquals(
        "json_object", requests.get(2).body().path("response_format").path("type").stringValue());
  }

  @Test
  void failedAuthenticationAndRateLimitAreSafeAndNeverRetried() {
    var probe = probe(null);
    response.set("private-upstream-secret-must-not-escape");
    status.set(401);
    assertEquals(
        "model_authentication_failed", probe.test(configuration(), TextModelRole.EMBEDDING));
    status.set(429);
    assertEquals("model_rate_limited", probe.test(configuration(), TextModelRole.GENERATION));
    assertEquals(2, requests.size());
    var old = new TextModels.Failure("model_http_failed");
    assertNull(old.httpStatus());
    assertEquals("model_http_failed", new TextModels.Failure("model_http_failed", 401).code());
  }

  @Test
  void invalidDimensionsAndUnverifiableQuoteDoNotPassConnectivityAsUsableProtocol() {
    var probe = probe(null);
    response.set(
        "{\"data\":[{\"index\":0,\"embedding\":[1,0,0]},{\"index\":1,\"embedding\":[0,1,0]}]}");
    assertEquals("model_invalid_response", probe.test(configuration(), TextModelRole.EMBEDDING));
    response.set(generation("模型虚构的文本"));
    assertEquals("model_invalid_response", probe.test(configuration(), TextModelRole.GENERATION));
    response.set(
        "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"refused\\\":true,\\\"quotes\\\":[]}\"}}]}");
    assertEquals("model_test_refused", probe.test(configuration(), TextModelRole.GENERATION));
    assertEquals(3, requests.size());
  }

  @Test
  void projectionUsesOnlyOneReadAndAbsentCollectionStillProvesConnection() {
    var probe =
        probe(
            new TextModelConnectionProbe.Projection(
                base, "synthetic-projection", "default", "java_probe"));
    response.set("{\"code\":0,\"data\":{\"has\":false}}");
    assertTrue(probe.projectionConfigured());
    assertNull(probe.test(configuration(), TextModelRole.PROJECTION));
    assertEquals(1, requests.size());
    assertEquals("/v2/vectordb/collections/has", requests.getFirst().path());
    assertEquals("Bearer synthetic-projection", requests.getFirst().authorization());
    assertEquals(2, requests.getFirst().body().size());
    assertEquals("java_probe", requests.getFirst().body().path("collectionName").stringValue());
    assertEquals(
        "projection_configuration_required",
        probe(null).test(configuration(), TextModelRole.PROJECTION));
    assertEquals(1, requests.size());
  }

  @Test
  void projectionProtocolFailuresAndTimeoutStayBoundedWithoutSecondDispatch() {
    var projection =
        new TextModelConnectionProbe.Projection(
            base, "synthetic-projection", "default", "java_probe");
    var probe = probe(projection);
    for (String invalid :
        List.of(
            "{\"code\":0,\"data\":{\"has\":1}}", "{\"code\":1,\"data\":{\"has\":true}}", "{} {}")) {
      response.set(invalid);
      assertEquals(
          "projection_invalid_response", probe.test(configuration(), TextModelRole.PROJECTION));
    }
    response.set("DELAY");
    var shortProbe =
        new TextModelConnectionProbe(
            base.resolve("/v1"), Duration.ofMillis(100), 1024, true, projection);
    long started = System.nanoTime();
    assertEquals("projection_timeout", shortProbe.test(configuration(), TextModelRole.PROJECTION));
    assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
    assertEquals(4, requests.size());
  }

  @Test
  void unsafeTrustedEndpointAndBudgetsAreRejectedBeforeAnyDispatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextModelConnectionProbe(base, Duration.ofSeconds(1), 1024, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextModelConnectionProbe(
                URI.create("https://synthetic.invalid/v1?key=bad"),
                Duration.ofSeconds(1),
                1024,
                false,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextModelConnectionProbe(base, Duration.ofSeconds(61), 1024, true, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextModelConnectionProbe(base, Duration.ofSeconds(1), 1023, true, null));
    assertEquals(0, requests.size());
  }

  private TextModelConnectionProbe probe(TextModelConnectionProbe.Projection projection) {
    return new TextModelConnectionProbe(
        base.resolve("/v1"), Duration.ofSeconds(3), 4096, true, projection);
  }

  private static TextModelConfiguration configuration() {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding("embed", "synthetic-embedding", 2, "pinned-v1"),
        new TextModelConfiguration.Role("rank", "synthetic-rerank"),
        new TextModelConfiguration.Role("generate", "synthetic-generation"));
  }

  private static String generation(String quote) {
    String content =
        JSON.writeValueAsString(
            Map.of(
                "refused",
                false,
                "quotes",
                List.of(Map.of("evidence_id", "synthetic-1", "quote", quote))));
    return JSON.writeValueAsString(
        Map.of(
            "choices",
            List.of(
                Map.of(
                    "index",
                    0,
                    "finish_reason",
                    "stop",
                    "message",
                    Map.of("role", "assistant", "content", content)))));
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.add(
        new Request(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            JSON.readTree(exchange.getRequestBody().readAllBytes())));
    String value = response.get();
    if ("DELAY".equals(value)) {
      try {
        Thread.sleep(700);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      value = "{\"code\":0,\"data\":{\"has\":true}}";
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    try {
      exchange.sendResponseHeaders(status.get(), bytes.length);
      exchange.getResponseBody().write(bytes);
    } finally {
      exchange.close();
    }
  }

  private record Request(String path, String authorization, JsonNode body) {}
}
