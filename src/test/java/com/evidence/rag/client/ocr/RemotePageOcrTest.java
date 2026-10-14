package com.evidence.rag.client.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.VisualImage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SiliconFlow chat/completions wire shape against a loopback fixture; no real provider. */
class RemotePageOcrTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sendsOnePaddleOcrVlPageRequestWithTheConfiguredKey() throws Exception {
    var seen = new AtomicReference<JsonNode>();
    var auth = new AtomicReference<String>();
    var image = new VisualImage("image/png", new byte[] {1, 2, 3});
    try (var ocr =
        client(
            200,
            exchange -> {
              auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
              seen.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
              return completion("stop", "标题\n预算650\u0000元");
            })) {
      assertEquals("标题\n预算650元", ocr.read(image));
    }
    assertEquals("Bearer sk-frontend-key", auth.get());
    var request = seen.get();
    assertEquals(PdfOcrOptions.REMOTE_MODEL, request.path("model").stringValue());
    assertFalse(request.path("stream").booleanValue());
    var content = request.path("messages").get(0).path("content");
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(image.content()),
        content.get(0).path("image_url").path("url").stringValue());
    assertEquals("OCR:", content.get(1).path("text").stringValue());
  }

  @Test
  void truncatedPageKeepsTextAndFailuresAreClassifiedForRetry() throws Exception {
    try (var ocr = client(200, exchange -> completion("length", "很长的一页"))) {
      assertEquals("很长的一页", ocr.read(image()));
    }
    for (int status : new int[] {429, 500, 503}) {
      try (var ocr = client(status, exchange -> "{}")) {
        assertTrue(assertThrows(PageOcr.Failure.class, () -> ocr.read(image())).retryable());
      }
    }
    for (int status : new int[] {400, 401, 403}) {
      try (var ocr = client(status, exchange -> "{}")) {
        assertFalse(assertThrows(PageOcr.Failure.class, () -> ocr.read(image())).retryable());
      }
    }
    try (var ocr = client(200, exchange -> completion("content_filter", "x"))) {
      assertTrue(assertThrows(PageOcr.Failure.class, () -> ocr.read(image())).retryable());
    }
    try (var ocr =
        new RemotePageOcr(
            URI.create("http://127.0.0.1:1/v1"), "sk-key", System.currentTimeMillis() + 60000)) {
      assertTrue(assertThrows(PageOcr.Failure.class, () -> ocr.read(image())).retryable());
    }
  }

  @Test
  void onlyHttpsOrLoopbackProvidersAndRealKeysAreAccepted() {
    long later = System.currentTimeMillis() + 60000;
    assertThrows(
        IllegalArgumentException.class,
        () -> new RemotePageOcr(URI.create("http://example.com/v1"), "sk-key", later));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RemotePageOcr(URI.create("https://api.siliconflow.cn/v1"), " ", later));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemotePageOcr(
                URI.create("https://api.siliconflow.cn/v1"),
                "sk-key",
                System.currentTimeMillis() - 1));
  }

  private interface Handler {
    String reply(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
  }

  private RemotePageOcr client(int status, Handler handler) throws Exception {
    if (server != null) {
      server.stop(0);
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            byte[] body = handler.reply(exchange).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception failed) {
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new RemotePageOcr(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
        "sk-frontend-key",
        System.currentTimeMillis() + 60000);
  }

  static String completion(String reason, String content) {
    return JSON.writeValueAsString(
        java.util.Map.of(
            "choices",
            java.util.List.of(
                java.util.Map.of(
                    "finish_reason",
                    reason,
                    "message",
                    java.util.Map.of("role", "assistant", "content", content)))));
  }

  private static VisualImage image() {
    return new VisualImage("image/png", new byte[] {1});
  }
}
