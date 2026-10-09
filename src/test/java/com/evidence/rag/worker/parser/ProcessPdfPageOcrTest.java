package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual isolated Java PDF process -> Unix frames; this is not provider quality evidence. */
class ProcessPdfPageOcrTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void abruptApplicationExitMakesThePdfWatchdogDisconnectThePageOcrPeer() throws Exception {
    Path socket = directory.resolve("parent.sock");
    try (var server = listener(socket)) {
      var accepted = new CountDownLatch(1);
      var serving =
          new FutureTask<>(
              () -> {
                try (var peer = server.accept()) {
                  request(peer);
                  accepted.countDown();
                  return peer.read(ByteBuffer.allocate(1));
                }
              });
      Thread.ofVirtual().start(serving);
      String classpath =
          String.join(
              File.pathSeparator,
              Arrays.stream(
                      System.getProperty(
                              "surefire.test.class.path", System.getProperty("java.class.path"))
                          .split(File.pathSeparator, -1))
                  .map(
                      value ->
                          Path.of(value.isEmpty() ? "." : value)
                              .toAbsolutePath()
                              .normalize()
                              .toString())
                  .toList());
      var builder =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Xmx128m",
                  "-cp",
                  classpath,
                  ParentFixture.class.getName(),
                  socket.toString())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.environment().clear();
      var parent = builder.start();
      List<ProcessHandle> workers = List.of();
      try {
        assertTrue(accepted.await(5, TimeUnit.SECONDS));
        workers = parent.descendants().toList();
        assertEquals(1, workers.size());
        assertTrue(workers.getFirst().isAlive());
        parent.destroyForcibly();
        assertTrue(parent.waitFor(3, TimeUnit.SECONDS));
        assertEquals(-1, serving.get(5, TimeUnit.SECONDS));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (workers.getFirst().isAlive() && System.nanoTime() < until) {
          Thread.sleep(10);
        }
        assertTrue(workers.stream().noneMatch(ProcessHandle::isAlive));
      } finally {
        parent.destroyForcibly();
        parent.waitFor();
        workers.forEach(ProcessHandle::destroyForcibly);
      }
    }
  }

  public static final class ParentFixture {
    public static void main(String[] args) throws Exception {
      try (var parser = parser(Path.of(args[0]), Duration.ofSeconds(60))) {
        parser.parse("pages.pdf", "application/pdf", pdf(1));
      }
    }
  }

  @Test
  void actualWorkerPreservesCompletePageOrderBlankPagesAndCodepointLocators() throws Exception {
    Path socket = directory.resolve("ocr.sock");
    try (var server = listener(socket);
        var parser = parser(socket, Duration.ofSeconds(15))) {
      var pageTexts = List.of("第一页😀\n预算650", "", "末页\n费用470");
      var serving =
          new FutureTask<>(
              () -> {
                for (String text : pageTexts) {
                  try (var peer = server.accept()) {
                    var request = request(peer);
                    assertEquals(6, request.size());
                    var png =
                        Base64.getDecoder().decode(request.path("image_base64").stringValue());
                    var rendered = ImageIO.read(new ByteArrayInputStream(png));
                    assertNotNull(rendered);
                    assertEquals(16, rendered.getWidth());
                    assertEquals(16, rendered.getHeight());
                    rendered.flush();
                    reply(
                        peer,
                        Map.of(
                            "version",
                            1,
                            "request_id",
                            request.path("request_id").stringValue(),
                            "profile",
                            PdfOcrOptions.PIPELINE_PROFILE,
                            "text",
                            text));
                  }
                }
                return null;
              });
      Thread.ofVirtual().start(serving);
      var parsed = parser.parse("pages.pdf", "application/pdf", pdf(3));
      serving.get(5, TimeUnit.SECONDS);
      assertEquals(pageTexts, parsed.pages().stream().map(page -> page.text()).toList());
      assertEquals(List.of(1, 2, 3), parsed.pages().stream().map(page -> page.number()).toList());
      assertEquals(
          List.of(1, 3), parsed.segments().stream().map(segment -> segment.page()).toList());
      for (var segment : parsed.segments()) {
        String page = parsed.pages().get(segment.page() - 1).text();
        assertEquals(
            segment.text(),
            page.substring(
                page.offsetByCodePoints(0, segment.start()),
                page.offsetByCodePoints(0, segment.end())));
      }
      assertEquals(
          "unchanged",
          parser
              .parse(
                  "notes.txt",
                  "text/plain",
                  "unchanged".getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .pages()
              .getFirst()
              .text());
    }
  }

  @Test
  void failedSecondPageCannotPublishTheSuccessfulFirstPage() throws Exception {
    Path socket = directory.resolve("ocr.sock");
    try (var server = listener(socket);
        var parser = parser(socket, Duration.ofSeconds(15))) {
      var serving =
          new FutureTask<>(
              () -> {
                for (int page = 0; page < 2; page++) {
                  try (var peer = server.accept()) {
                    var request = request(peer);
                    if (page == 0) {
                      reply(
                          peer,
                          Map.of(
                              "version",
                              1,
                              "request_id",
                              request.path("request_id").stringValue(),
                              "profile",
                              PdfOcrOptions.PIPELINE_PROFILE,
                              "text",
                              "successful first page"));
                    } else {
                      reply(
                          peer,
                          Map.of(
                              "version",
                              1,
                              "request_id",
                              request.path("request_id").stringValue(),
                              "error_code",
                              "pipeline_failed"));
                    }
                  }
                }
                return null;
              });
      Thread.ofVirtual().start(serving);
      assertEquals(
          "parser_failed",
          assertThrows(
                  TextParser.Failure.class,
                  () -> parser.parse("pages.pdf", "application/pdf", pdf(2)))
              .code());
      serving.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void closeAndDeadlineDisconnectBlockedWorkerSocketWithoutReturningPartialPages()
      throws Exception {
    for (boolean cancel : List.of(true, false)) {
      Path socket = directory.resolve(cancel ? "close.sock" : "timeout.sock");
      try (var server = listener(socket);
          var parser = parser(socket, Duration.ofSeconds(cancel ? 15 : 3))) {
        var accepted = new CountDownLatch(1);
        var serving =
            new FutureTask<>(
                () -> {
                  try (var peer = server.accept()) {
                    request(peer);
                    accepted.countDown();
                    return peer.read(ByteBuffer.allocate(1));
                  }
                });
        Thread.ofVirtual().start(serving);
        var parsing =
            new FutureTask<>(
                () ->
                    assertThrows(
                            TextParser.Failure.class,
                            () -> parser.parse("pages.pdf", "application/pdf", pdf(1)))
                        .code());
        Thread.ofVirtual().start(parsing);
        assertTrue(accepted.await(5, TimeUnit.SECONDS));
        if (cancel) {
          parser.close();
        }
        assertEquals(cancel ? "parser_closed" : "parser_timeout", parsing.get(6, TimeUnit.SECONDS));
        assertEquals(-1, serving.get(3, TimeUnit.SECONDS));
      }
    }
  }

  private static ProcessTextParser parser(Path socket, Duration deadline) {
    return new ProcessTextParser(
        deadline, new PdfOcrOptions(null, socket, PdfOcrOptions.PIPELINE_PROFILE));
  }

  private static byte[] pdf(int pages) throws Exception {
    return PdfOcrCompilerTest.pdf(pages, false, new PDRectangle(8, 8));
  }

  private static ServerSocketChannel listener(Path socket) throws Exception {
    var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socket));
    return server;
  }

  private static JsonNode request(SocketChannel peer) throws Exception {
    var header = ByteBuffer.allocate(4);
    read(peer, header);
    int length = header.flip().getInt();
    assertTrue(length > 0 && length <= 15 * 1024 * 1024);
    var body = ByteBuffer.allocate(length);
    read(peer, body);
    var request = JSON.readTree(body.array());
    assertEquals(PdfOcrOptions.PIPELINE_PROFILE, request.path("profile").stringValue());
    return request;
  }

  private static void reply(SocketChannel peer, Map<String, Object> response) throws Exception {
    byte[] bytes = JSON.writeValueAsBytes(response);
    write(peer, ByteBuffer.allocate(4).putInt(bytes.length).flip());
    write(peer, ByteBuffer.wrap(bytes));
  }

  private static void read(SocketChannel peer, ByteBuffer bytes) throws Exception {
    while (bytes.hasRemaining()) {
      assertTrue(peer.read(bytes) >= 0);
    }
  }

  private static void write(SocketChannel peer, ByteBuffer bytes) throws Exception {
    while (bytes.hasRemaining()) {
      peer.write(bytes);
    }
  }
}
