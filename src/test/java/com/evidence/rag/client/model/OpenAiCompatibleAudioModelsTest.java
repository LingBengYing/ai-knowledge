package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleAudioModelsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void sendsExactPcmWavAsMultipartAndConsumesOnlyTranscriptText() throws Exception {
    byte[] wav = wav(32_000);
    for (int index = 44; index < wav.length; index++) {
      wav[index] = (byte) index;
    }
    byte[] original = wav.clone();
    String text = "会议改在周三。\n保留原话 😀";
    try (var fixture = new Fixture()) {
      fixture.response.set(
          JSON.writeValueAsBytes(
              Map.of(
                  "text",
                  text,
                  "timestamp",
                  "untrusted-time",
                  "confidence",
                  1,
                  "segments",
                  List.of(Map.of("start", 99999, "text", "not evidence")))));
      assertTrue(fixture.requests.isEmpty(), "Construction must not probe a provider");
      AudioModels models = fixture.models;
      var transcript = models.transcribe(wav);
      assertEquals(text, transcript.text());
      assertEquals("Transcript[redacted]", transcript.toString());
      assertArrayEquals(original, wav);

      var request = fixture.requests.poll();
      assertNotNull(request);
      assertEquals("POST", request.method());
      assertEquals("/v1/audio/transcriptions", request.path());
      assertEquals("Bearer " + fixture.key, request.authorization());
      assertEquals("application/json", request.accept());
      assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
      String boundary = request.contentType().substring("multipart/form-data; boundary=".length());
      assertTrue(boundary.matches("[A-Za-z0-9_-]{16,70}"));
      assertFalse(new String(wav, StandardCharsets.ISO_8859_1).contains(boundary));
      String wire = new String(request.body(), StandardCharsets.ISO_8859_1);
      String[] parts = wire.split(java.util.regex.Pattern.quote("--" + boundary), -1);
      assertEquals(4, parts.length);
      assertEquals("", parts[0]);
      assertEquals("--\r\n", parts[3]);
      String modelPart =
          Arrays.stream(parts)
              .filter(part -> part.contains("name=\"model\""))
              .findFirst()
              .orElseThrow();
      assertEquals(
          "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nsynthetic-asr\r\n", modelPart);
      String filePart =
          Arrays.stream(parts)
              .filter(part -> part.contains("name=\"file\""))
              .findFirst()
              .orElseThrow();
      String fileHeader =
          "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
      assertTrue(filePart.startsWith(fileHeader));
      assertTrue(filePart.endsWith("\r\n"));
      assertArrayEquals(
          wav,
          filePart
              .substring(fileHeader.length(), filePart.length() - 2)
              .getBytes(StandardCharsets.ISO_8859_1));
      assertTrue(fixture.requests.isEmpty(), "One chunk must cause exactly one request");
    }
  }

  @Test
  void acceptsThirtySecondChunkAndBlankSilenceWithoutInventingFacts() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.response.set(JSON.writeValueAsBytes(Map.of("text", " \n\t")));
      assertEquals(" \n\t", fixture.models.transcribe(wav(960_000)).text());
      assertNotNull(fixture.requests.poll());
      fixture.response.set(JSON.writeValueAsBytes(Map.of("text", "")));
      assertEquals("", fixture.models.transcribe(wav(2)).text());
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void rejectsNonCanonicalHeadersAndLengthsBeforeSendingAnything() throws Exception {
    var invalid = new ArrayList<byte[]>();
    invalid.add(new byte[0]);
    invalid.add(new byte[43]);
    invalid.add(wav(0));
    invalid.add(wav(1));
    invalid.add(wav(960_002));
    invalid.add(Arrays.copyOf(wav(2), 48));
    invalid.add(Arrays.copyOf(wav(2), 45));
    for (int offset : List.of(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40)) {
      byte[] damaged = wav(2);
      damaged[offset] ^= 1;
      invalid.add(damaged);
    }
    try (var fixture = new Fixture()) {
      assertFailure("model_invalid_input", () -> fixture.models.transcribe(null));
      for (byte[] bytes : invalid) {
        assertFailure("model_invalid_input", () -> fixture.models.transcribe(bytes));
      }
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void transcriptRejectsMalformedUnicodeControlsAndOversizeInsteadOfTruncating() {
    assertEquals("😀".repeat(4096), new AudioModels.Transcript("😀".repeat(4096)).text());
    assertEquals("\r\n\t", new AudioModels.Transcript("\r\n\t").text());
    assertEquals("", new AudioModels.Transcript("").text());
    assertFailure("model_invalid_response", () -> new AudioModels.Transcript(null));
    for (String invalid :
        List.of(
            "x".repeat(4097),
            "😀".repeat(4097),
            "\u0000",
            "\u000b",
            "\u007f",
            "\u0085",
            "\ud800",
            "\udc00",
            "\ud800x")) {
      assertFailure("model_invalid_response", () -> new AudioModels.Transcript(invalid));
    }
  }

  @Test
  void rejectsInvalidTextAndStrictJsonResponsesWithoutRetry() throws Exception {
    try (var fixture = new Fixture()) {
      for (String response :
          List.of(
              "{}",
              "[]",
              "null",
              "{\"text\":null}",
              "{\"text\":1}",
              "{\"text\":\"a\",\"text\":\"b\"}",
              "{\"text\":\"a\"} {}",
              "{\"text\":\"\\u0000\"}",
              "{\"text\":\"\\ud800\"}",
              JSON.writeValueAsString(Map.of("text", "x".repeat(4097))))) {
        fixture.response.set(response.getBytes(StandardCharsets.UTF_8));
        assertFailure("model_invalid_response", () -> fixture.models.transcribe(wav(2)));
        assertNotNull(fixture.requests.poll());
        assertTrue(fixture.requests.isEmpty(), "Invalid responses must not trigger retries");
      }
      fixture.response.set(new byte[] {(byte) 0xc3, (byte) 0x28});
      assertFailure("model_invalid_response", () -> fixture.models.transcribe(wav(2)));
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void enforcesResponseByteBudgetAndJsonContentType() throws Exception {
    try (var fixture = new Fixture(Duration.ofSeconds(3), 1024)) {
      fixture.response.set(JSON.writeValueAsBytes(Map.of("text", "x".repeat(1024))));
      assertFailure("model_response_too_large", () -> fixture.models.transcribe(wav(2)));
      assertNotNull(fixture.requests.poll());
      fixture.response.set(JSON.writeValueAsBytes(Map.of("text", "valid")));
      fixture.contentType.set("text/plain");
      assertFailure("model_invalid_response", () -> fixture.models.transcribe(wav(2)));
      assertNotNull(fixture.requests.poll());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void failsHttpAndRedirectResponsesWithoutRetryOrFollowingLocation() throws Exception {
    try (var fixture = new Fixture()) {
      for (int status : List.of(307, 429, 500)) {
        fixture.status.set(status);
        fixture.response.set(JSON.writeValueAsBytes(Map.of("text", fixture.key)));
        assertFailure("model_http_failed", () -> fixture.models.transcribe(wav(2)));
        assertNotNull(fixture.requests.poll());
        assertTrue(fixture.requests.isEmpty());
        assertEquals(0, fixture.redirectRequests.get());
      }
    }
  }

  @Test
  void preservesInterruptionAndRejectsClosedClientWithoutSending() throws Exception {
    try (var fixture = new Fixture()) {
      Thread.currentThread().interrupt();
      try {
        assertFailure("model_interrupted", () -> fixture.models.transcribe(wav(2)));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      fixture.models.close();
      assertFailure("model_closed", () -> fixture.models.transcribe(wav(2)));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void totalDeadlineStopsDelayedResponse() throws Exception {
    try (var fixture = new Fixture(Duration.ofMillis(100), 65536)) {
      fixture.delayMillis.set(500);
      long started = System.nanoTime();
      assertFailure("model_timeout", () -> fixture.models.transcribe(wav(2)));
      assertTrue(
          Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }
  }

  @Test
  void freezesRevisionToProtocolUrlAndModelButNotCredentials() {
    URI base = URI.create("https://example.invalid/v1");
    String key = UUID.randomUUID().toString();
    var first = new OpenAiCompatibleModels.Endpoint(base, "synthetic-asr", key);
    var rotated =
        new OpenAiCompatibleModels.Endpoint(base, "synthetic-asr", UUID.randomUUID().toString());
    var changedModel = new OpenAiCompatibleModels.Endpoint(base, "synthetic-asr-v2", key);
    var changedUrl =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("https://example.invalid/v2"), "synthetic-asr", key);
    var configuration =
        new OpenAiCompatibleAudioModels.Configuration(
            first, Duration.ofSeconds(60), 1_048_576, false);
    try (AudioModels a = new OpenAiCompatibleAudioModels(configuration);
        AudioModels b =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    rotated, Duration.ofSeconds(1), 1024, false));
        AudioModels c =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    changedModel, Duration.ofSeconds(60), 65536, false));
        AudioModels d =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    changedUrl, Duration.ofSeconds(60), 65536, false))) {
      assertEquals(a.revision(), b.revision());
      assertNotEquals(a.revision(), c.revision());
      assertNotEquals(a.revision(), d.revision());
      assertTrue(a.revision().matches("java-audio-models-v1-[a-f0-9]{64}"));
      assertFalse(a.revision().contains(key));
      assertEquals("Configuration[redacted]", configuration.toString());
    }
  }

  @Test
  void rejectsInvalidConfigurationBeforeConstructionCanCallNetwork() {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("https://example.invalid/v1"),
            "synthetic-asr",
            UUID.randomUUID().toString());
    assertFailure("model_invalid_configuration", () -> new OpenAiCompatibleAudioModels(null));
    for (Duration deadline : List.of(Duration.ZERO, Duration.ofMillis(9), Duration.ofSeconds(61))) {
      assertFailure(
          "model_invalid_configuration",
          () -> new OpenAiCompatibleAudioModels.Configuration(endpoint, deadline, 65536, false));
    }
    assertFailure(
        "model_invalid_configuration",
        () -> new OpenAiCompatibleAudioModels.Configuration(endpoint, null, 65536, false));
    for (int bytes : List.of(1023, 1_048_577)) {
      assertFailure(
          "model_invalid_configuration",
          () ->
              new OpenAiCompatibleAudioModels.Configuration(
                  endpoint, Duration.ofSeconds(1), bytes, false));
    }
    assertFailure(
        "model_invalid_configuration",
        () ->
            new OpenAiCompatibleAudioModels.Configuration(
                null, Duration.ofSeconds(1), 1024, false));
    var local =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1/v1"), "synthetic-asr", UUID.randomUUID().toString());
    assertFailure(
        "model_invalid_configuration",
        () ->
            new OpenAiCompatibleAudioModels.Configuration(
                local, Duration.ofSeconds(1), 1024, false));
    assertNotNull(
        new OpenAiCompatibleAudioModels.Configuration(local, Duration.ofMillis(10), 1024, true));
  }

  private static void assertFailure(String code, Executable operation) {
    var failure = assertThrows(TextModels.Failure.class, operation);
    assertEquals(code, failure.code());
    assertEquals("模型调用或配置未通过安全校验。", failure.getMessage());
    assertNull(failure.getCause());
  }

  private static byte[] wav(int dataBytes) {
    return ByteBuffer.allocate(44 + dataBytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(36 + dataBytes)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16_000)
        .putInt(32_000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(dataBytes)
        .array();
  }

  private static final class Fixture implements AutoCloseable {
    final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    final AtomicReference<byte[]> response =
        new AtomicReference<>("{\"text\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8));
    final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger delayMillis = new AtomicInteger();
    final AtomicInteger redirectRequests = new AtomicInteger();
    final String key = UUID.randomUUID().toString();
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    final OpenAiCompatibleAudioModels models;

    Fixture() throws IOException {
      this(Duration.ofSeconds(3), 65536);
    }

    Fixture(Duration deadline, int maxResponseBytes) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::respond);
      server.start();
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "synthetic-asr",
              key);
      models =
          new OpenAiCompatibleAudioModels(
              new OpenAiCompatibleAudioModels.Configuration(
                  endpoint, deadline, maxResponseBytes, true));
    }

    private void respond(HttpExchange exchange) throws IOException {
      try (exchange) {
        if (exchange.getRequestURI().getPath().equals("/redirect")) {
          redirectRequests.incrementAndGet();
        }
        byte[] input = exchange.getRequestBody().readNBytes(1_048_577);
        requests.add(
            new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Accept"),
                input));
        try {
          Thread.sleep(delayMillis.get());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        byte[] reply = response.get();
        exchange.getResponseHeaders().set("Content-Type", contentType.get());
        exchange.getResponseHeaders().set("Location", "/redirect");
        exchange.sendResponseHeaders(status.get(), reply.length);
        exchange.getResponseBody().write(reply);
      }
    }

    @Override
    public void close() {
      models.close();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  private record Request(
      String method,
      String path,
      String authorization,
      String contentType,
      String accept,
      byte[] body) {
    @Override
    public String toString() {
      return "Request[redacted]";
    }
  }
}
