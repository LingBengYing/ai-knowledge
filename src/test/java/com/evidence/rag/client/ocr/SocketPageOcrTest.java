package com.evidence.rag.client.ocr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.VisualImage;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SocketPageOcrTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;
  private int socketNumber;

  @Test
  void fragmentedBoundedFramesCarryActualPngProfileAndDeadlineWithoutCredentials()
      throws Exception {
    var image = new VisualImage("image/png", new byte[] {1, 2, 3});
    String text = "标题😀\n|字段|值|\n|预算|650|";
    try (var fixture =
            new Fixture(
                request -> {
                  assertEquals(6, request.size());
                  assertEquals(1, request.path("version").intValue());
                  assertEquals("image/png", request.path("mime").stringValue());
                  assertEquals(
                      PdfOcrOptions.PIPELINE_PROFILE, request.path("profile").stringValue());
                  UUID.fromString(request.path("request_id").stringValue());
                  assertTrue(
                      request.path("deadline_epoch_ms").longValue() > System.currentTimeMillis());
                  assertArrayEquals(
                      image.content(),
                      Base64.getDecoder().decode(request.path("image_base64").stringValue()));
                  return reply(request, text);
                });
        var client = client(fixture.path)) {
      assertEquals(text, client.read(image));
      fixture.done.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void completeBlankPageIsNotConfusedWithFailure() throws Exception {
    try (var fixture = new Fixture(request -> reply(request, ""));
        var client = client(fixture.path)) {
      assertEquals("", client.read(image()));
      fixture.done.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void malformedMismatchedOversizedAndFailedResponsesNeverBecomeBlankPages() throws Exception {
    for (String mode :
        new String[] {
          "id",
          "profile",
          "version",
          "extra",
          "type",
          "duplicate",
          "trailing",
          "large_frame",
          "truncated",
          "too_many_points",
          "controls",
          "error",
          "unknown_error"
        }) {
      try (var fixture =
              new Fixture(
                  request -> {
                    var response = JSON.readTree(reply(request, "text")).deepCopy();
                    var object = (tools.jackson.databind.node.ObjectNode) response;
                    switch (mode) {
                      case "id" -> object.put("request_id", UUID.randomUUID().toString());
                      case "profile" -> object.put("profile", "other");
                      case "version" -> object.put("version", 2);
                      case "extra" -> object.put("secret", "must-not-be-returned");
                      case "type" -> object.put("text", 17);
                      case "too_many_points" -> object.put("text", "x".repeat(1_000_001));
                      case "controls" -> object.put("text", "unsafe\u0000");
                      case "duplicate" -> {
                        return (new String(reply(request, "text"), StandardCharsets.UTF_8)
                                .replace("\"version\":1", "\"version\":1,\"version\":1"))
                            .getBytes(StandardCharsets.UTF_8);
                      }
                      case "trailing" -> {
                        return (new String(reply(request, "text"), StandardCharsets.UTF_8) + " {}")
                            .getBytes(StandardCharsets.UTF_8);
                      }
                      case "large_frame" -> {
                        return new byte[4 * 1024 * 1024 + 1];
                      }
                      case "truncated" -> {
                        return new byte[] {'{'};
                      }
                      case "error", "unknown_error" -> {
                        return JSON.writeValueAsBytes(
                            Map.of(
                                "version",
                                1,
                                "request_id",
                                request.path("request_id").stringValue(),
                                "error_code",
                                mode.equals("error")
                                    ? "pipeline_failed"
                                    : "arbitrary upstream body"));
                      }
                      default -> fail(mode);
                    }
                    return JSON.writeValueAsBytes(response);
                  });
          var client = client(fixture.path)) {
        assertEquals(
            "page_ocr_failed",
            assertThrows(PageOcr.Failure.class, () -> client.read(image()), mode).getMessage());
        fixture.done.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void closeInterruptsBlockedResponseAndClosesPeerWithoutReturningPartialText() throws Exception {
    Path socket = directory.resolve("blocked.sock");
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        var client = client(socket)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      var accepted = new CountDownLatch(1);
      var peer =
          new FutureTask<>(
              () -> {
                try (var channel = server.accept()) {
                  request(channel);
                  accepted.countDown();
                  return channel.read(ByteBuffer.allocate(1));
                }
              });
      Thread.ofVirtual().start(peer);
      var read =
          new FutureTask<>(() -> assertThrows(PageOcr.Failure.class, () -> client.read(image())));
      Thread.ofVirtual().start(read);
      assertTrue(accepted.await(5, TimeUnit.SECONDS));
      client.close();
      assertNotNull(read.get(2, TimeUnit.SECONDS));
      assertEquals(-1, peer.get(2, TimeUnit.SECONDS));
      assertThrows(PageOcr.Failure.class, () -> client.read(image()));
    }
  }

  @Test
  void missingRegularOrSymlinkPathsAndExpiredRequestsNeverConnect() throws Exception {
    Path regular = directory.resolve("regular");
    Files.writeString(regular, "not a socket");
    Path link = directory.resolve("link");
    Files.createSymbolicLink(link, regular);
    for (Path socket : new Path[] {regular, link, directory, directory.resolve("missing")}) {
      assertFalse(SocketPageOcr.isNativeSocket(socket));
      try (var client = client(socket)) {
        assertThrows(PageOcr.Failure.class, () -> client.read(image()));
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new SocketPageOcr(regular, System.currentTimeMillis() - 1));
  }

  private SocketPageOcr client(Path socket) {
    return new SocketPageOcr(socket, System.currentTimeMillis() + 15000);
  }

  private static VisualImage image() {
    return new VisualImage("image/png", new byte[] {1});
  }

  private static byte[] reply(JsonNode request, String text) {
    return JSON.writeValueAsBytes(
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

  private static JsonNode request(SocketChannel channel) throws Exception {
    var header = ByteBuffer.allocate(4);
    read(channel, header);
    int length = header.flip().getInt();
    assertTrue(length > 0 && length <= 15 * 1024 * 1024);
    var body = ByteBuffer.allocate(length);
    read(channel, body);
    return JSON.readTree(body.array());
  }

  private static void read(SocketChannel channel, ByteBuffer buffer) throws Exception {
    while (buffer.hasRemaining()) {
      assertTrue(channel.read(buffer) >= 0);
    }
  }

  private final class Fixture implements AutoCloseable {
    private final Path path = directory.resolve("ocr" + (++socketNumber) + ".sock");
    private final ServerSocketChannel server;
    private final FutureTask<Void> done;

    Fixture(Function<JsonNode, byte[]> response) throws Exception {
      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      server.bind(UnixDomainSocketAddress.of(path));
      done =
          new FutureTask<>(
              () -> {
                try (var channel = server.accept()) {
                  var body = response.apply(request(channel));
                  var header = ByteBuffer.allocate(4).putInt(body.length).flip();
                  while (header.hasRemaining()) {
                    var one = ByteBuffer.wrap(new byte[] {header.get()});
                    while (one.hasRemaining()) {
                      channel.write(one);
                    }
                  }
                  if (body.length <= 4 * 1024 * 1024) {
                    var payload = ByteBuffer.wrap(body);
                    while (payload.hasRemaining()) {
                      channel.write(payload);
                    }
                  }
                  return null;
                }
              });
      Thread.ofVirtual().start(done);
    }

    @Override
    public void close() throws Exception {
      server.close();
    }
  }
}
