package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.domain.TextModelRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class TextModelConnectionProbeBoundaryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> contentType = new AtomicReference<>("application/json");
  private final AtomicReference<byte[]> response =
      new AtomicReference<>(
          "{\"code\":0,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8));
  private final CountDownLatch received = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private volatile boolean block;
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  @ParameterizedTest
  @CsvSource({
    "401,projection_authentication_failed",
    "403,projection_authentication_failed",
    "429,projection_rate_limited",
    "503,projection_unreachable",
    "302,projection_unreachable"
  })
  void projectionHttpFailuresAreSafeAndNeverRedirectedOrRetried(int httpStatus, String code) {
    status.set(httpStatus);
    response.set("synthetic upstream private error".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        code, probe(Duration.ofSeconds(3), 4096).test(configuration(), TextModelRole.PROJECTION));
    assertEquals(1, requests.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong_mime",
        "missing_mime",
        "invalid_utf8",
        "large",
        "missing_code",
        "fractional_code",
        "overflow_code",
        "duplicate_code",
        "missing_has"
      })
  void malformedProjectionResponsesNeverPassAndStayWithinTheResponseLimit(String shape) {
    switch (shape) {
      case "wrong_mime" -> contentType.set("text/plain");
      case "missing_mime" -> contentType.set(null);
      case "invalid_utf8" -> response.set(new byte[] {(byte) 0xc3, 0x28});
      case "large" -> response.set("x".repeat(4097).getBytes(StandardCharsets.UTF_8));
      case "missing_code" ->
          response.set("{\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8));
      case "fractional_code" ->
          response.set("{\"code\":0.5,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8));
      case "overflow_code" ->
          response.set(
              "{\"code\":2147483648,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8));
      case "duplicate_code" ->
          response.set(
              "{\"code\":0,\"code\":0,\"data\":{\"has\":true}}".getBytes(StandardCharsets.UTF_8));
      case "missing_has" ->
          response.set("{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8));
      default -> throw new AssertionError(shape);
    }
    assertEquals(
        "projection_invalid_response",
        probe(Duration.ofSeconds(3), 1024).test(configuration(), TextModelRole.PROJECTION));
    assertEquals(1, requests.get());
  }

  @Test
  void missingProjectionListenerHasAnUnreachableResultInsteadOfAuthenticationOrProtocolSuccess()
      throws Exception {
    int unusedPort;
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      unusedPort = socket.getLocalPort();
    }
    var absent =
        new TextModelConnectionProbe.Projection(
            URI.create("http://127.0.0.1:" + unusedPort),
            "synthetic-token",
            "default",
            "java_probe");
    var probe =
        new TextModelConnectionProbe(
            endpoint.resolve("/v1"), Duration.ofSeconds(2), 4096, true, absent);
    assertEquals("projection_unreachable", probe.test(configuration(), TextModelRole.PROJECTION));
    assertEquals(0, requests.get());
  }

  @Test
  void cancellationBeforeDispatchAndDuringTheProjectionResponseIsPreserved() throws Exception {
    var probe = probe(Duration.ofSeconds(5), 4096);
    Thread.currentThread().interrupt();
    try {
      assertEquals("projection_interrupted", probe.test(configuration(), TextModelRole.PROJECTION));
      assertEquals("model_interrupted", probe.test(configuration(), TextModelRole.EMBEDDING));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, requests.get());
    } finally {
      Thread.interrupted();
    }
    block = true;
    var result = new AtomicReference<String>();
    Thread caller =
        Thread.startVirtualThread(
            () -> result.set(probe.test(configuration(), TextModelRole.PROJECTION)));
    try {
      assertTrue(received.await(5, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(5000);
      assertFalse(caller.isAlive());
      assertEquals("projection_interrupted", result.get());
      assertEquals(1, requests.get());
    } finally {
      release.countDown();
      caller.interrupt();
    }
  }

  @Test
  void modelResponseTimeoutAnd403AreClassifiedWithoutRetry() {
    status.set(403);
    response.set("synthetic denied body".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "model_authentication_failed",
        probe(Duration.ofSeconds(3), 4096).test(configuration(), TextModelRole.RERANK));
    status.set(200);
    block = true;
    assertEquals(
        "model_timeout",
        probe(Duration.ofMillis(300), 4096).test(configuration(), TextModelRole.EMBEDDING));
    assertEquals(2, requests.get());
  }

  @Test
  void aValidQuotedFragmentWithoutTheSyntheticAnswerDoesNotPassTheGenerationProbe() {
    String inner =
        JSON.writeValueAsString(
            Map.of(
                "refused",
                false,
                "quotes",
                List.of(Map.of("evidence_id", "synthetic-1", "quote", "合成资料中的核验词"))));
    response.set(
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
                        Map.of("role", "assistant", "content", inner))))));
    assertEquals(
        "model_test_refused",
        probe(Duration.ofSeconds(3), 4096).test(configuration(), TextModelRole.GENERATION));
    assertEquals(1, requests.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "v1/models",
        "mailto:synthetic@example.invalid",
        "https:///v1",
        "https://user@example.invalid/v1",
        "https://example.invalid/v1#fragment",
        "https://example.invalid:0/v1",
        "https://example.invalid:65536/v1",
        "https://example.invalid/v%31",
        "https://example.invalid/./v1",
        "https://example.invalid/../v1",
        "ftp://example.invalid/v1",
        "http://localhost/v1"
      })
  void operatorEndpointMistakesAreRejectedBeforeAnyCredentialCanBeSent(String address) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextModelConnectionProbe(
                URI.create(address), Duration.ofSeconds(1), 4096, true, null));
    assertEquals(0, requests.get());
  }

  @Test
  void
      projectionRequiresARootEndpointAndQualifiedSafeNamesButLiteralIpv6NeedsNoNetworkValidation() {
    var projection =
        new TextModelConnectionProbe.Projection(
            endpoint.resolve("/wrong-path"), "synthetic-token", "default", "java_probe");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextModelConnectionProbe(endpoint, Duration.ofSeconds(1), 4096, true, projection));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextModelConnectionProbe.Projection(
                endpoint, "bad token", "default", "java_probe"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextModelConnectionProbe.Projection(
                endpoint, "synthetic-token", "bad/name", "java_probe"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TextModelConnectionProbe.Projection(
                endpoint, "synthetic-token", "default", "foreign_collection"));
    var local =
        new TextModelConnectionProbe(
            URI.create("http://[::1]:1/v1"), Duration.ofSeconds(1), 4096, true, null);
    assertFalse(local.projectionConfigured());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextModelConnectionProbe(endpoint, Duration.ZERO, 4096, true, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextModelConnectionProbe(endpoint, Duration.ofSeconds(1), 4194305, true, null));
    assertEquals(0, requests.get());
  }

  private TextModelConnectionProbe probe(Duration deadline, int cap) {
    return new TextModelConnectionProbe(
        endpoint.resolve("/v1"),
        deadline,
        cap,
        true,
        new TextModelConnectionProbe.Projection(
            endpoint, "synthetic-token", "default", "java_probe"));
  }

  private static TextModelConfiguration configuration() {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding("embed", "synthetic-key", 2, "pinned-v1"),
        new TextModelConfiguration.Role("rank", "synthetic-key"),
        new TextModelConfiguration.Role("generate", "synthetic-key"));
  }

  private void handle(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    requests.incrementAndGet();
    received.countDown();
    if (block) {
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    try {
      if (contentType.get() != null) {
        exchange.getResponseHeaders().set("Content-Type", contentType.get());
      }
      exchange
          .getResponseHeaders()
          .set("Location", endpoint.resolve("/redirect-must-not-be-followed").toString());
      byte[] bytes = response.get();
      exchange.sendResponseHeaders(status.get(), bytes.length);
      exchange.getResponseBody().write(bytes);
    } finally {
      exchange.close();
    }
  }
}
