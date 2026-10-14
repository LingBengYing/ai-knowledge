package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.tool.parser.TextParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The actual isolated worker calls the provider directly with the key it received on stdin. */
class ProcessPdfRemoteOcrTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void workerRecognizesEveryScannedPageWithTheManagedKey() throws Exception {
    var keys = new ConcurrentLinkedQueue<String>();
    var provider = provider(200, keys);
    try (var parser =
        new ProcessTextParser(
            Duration.ofSeconds(60), PdfOcrOptions.remote(provider), "sk-frontend-key")) {
      var parsed =
          parser.parse(
              "scan.pdf",
              "application/pdf",
              PdfOcrCompilerTest.pdf(3, false, new PDRectangle(8, 8)));
      assertEquals(
          List.of("识别正文", "识别正文", "识别正文"),
          parsed.pages().stream().map(page -> page.text()).toList());
    }
    assertEquals(3, keys.size());
    assertTrue(keys.stream().allMatch("Bearer sk-frontend-key"::equals));
  }

  @Test
  void rejectedKeyFailsTheDocumentWithoutRetrying() throws Exception {
    var keys = new ConcurrentLinkedQueue<String>();
    var provider = provider(401, keys);
    try (var parser =
        new ProcessTextParser(Duration.ofSeconds(60), PdfOcrOptions.remote(provider), "sk-bad")) {
      var failure =
          assertThrows(
              TextParser.Failure.class,
              () ->
                  parser.parse(
                      "scan.pdf",
                      "application/pdf",
                      PdfOcrCompilerTest.pdf(1, false, new PDRectangle(8, 8))));
      assertEquals("parser_failed", failure.code());
    }
    assertEquals(1, keys.size());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessTextParser(Duration.ofSeconds(60), PdfOcrOptions.remote(provider), null));
  }

  private URI provider(int status, ConcurrentLinkedQueue<String> keys) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              JSON.writeValueAsString(
                      Map.of(
                          "choices",
                          List.of(
                              Map.of(
                                  "finish_reason",
                                  "stop",
                                  "message",
                                  Map.of("role", "assistant", "content", "识别正文")))))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
  }
}
