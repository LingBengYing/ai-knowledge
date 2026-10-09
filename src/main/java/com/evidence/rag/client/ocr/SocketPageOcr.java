package com.evidence.rag.client.ocr;

import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.VisualImage;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One bounded frame per connection to the preconfigured local page-recognition Module. */
public final class SocketPageOcr implements PageOcr {
  private static final int MAX_REQUEST = 15 * 1024 * 1024;
  private static final int MAX_RESPONSE = 4 * 1024 * 1024;
  private static final Set<String> ERRORS =
      Set.of(
          "invalid_request",
          "request_too_large",
          "deadline_exceeded",
          "cancelled",
          "busy",
          "upstream_unavailable",
          "invalid_model_response",
          "pipeline_failed",
          "response_too_large");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(4)
                          .maxStringLength(2_000_000)
                          .maxNumberLength(20)
                          .build())
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Path socket;
  private final long deadlineEpochMs;
  private final Object lock = new Object();
  private SocketChannel active;
  private boolean closed;

  public SocketPageOcr(Path socket, long deadlineEpochMs) {
    // Reuse the immutable path contract; this constructor never accepts an upload-provided path.
    new PdfOcrOptions(null, socket, PdfOcrOptions.PIPELINE_PROFILE);
    if (deadlineEpochMs <= System.currentTimeMillis()) {
      throw new IllegalArgumentException("Page OCR deadline has expired");
    }
    this.socket = socket;
    this.deadlineEpochMs = deadlineEpochMs;
  }

  public static boolean isNativeSocket(Path path) {
    try {
      return ((Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0170000)
          == 0140000;
    } catch (IOException | UnsupportedOperationException | IllegalArgumentException failure) {
      return false;
    }
  }

  @Override
  public String read(VisualImage image) {
    if (image == null
        || !"image/png".equals(image.mediaType())
        || image.content().length == 0
        || image.content().length > 10 * 1024 * 1024
        || System.currentTimeMillis() >= deadlineEpochMs
        || !isNativeSocket(socket)) {
      throw new Failure();
    }
    String id = UUID.randomUUID().toString();
    byte[] request =
        JSON.writeValueAsBytes(
            Map.of(
                "version",
                1,
                "request_id",
                id,
                "profile",
                PdfOcrOptions.PIPELINE_PROFILE,
                "deadline_epoch_ms",
                deadlineEpochMs,
                "mime",
                "image/png",
                "image_base64",
                Base64.getEncoder().encodeToString(image.content())));
    if (request.length > MAX_REQUEST) {
      throw new Failure();
    }
    SocketChannel channel = null;
    try {
      synchronized (lock) {
        if (closed || active != null) {
          throw new Failure();
        }
        channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        active = channel;
      }
      channel.connect(UnixDomainSocketAddress.of(socket));
      write(channel, ByteBuffer.allocate(4).putInt(request.length).flip());
      write(channel, ByteBuffer.wrap(request));
      var header = ByteBuffer.allocate(4);
      read(channel, header);
      int length = header.flip().getInt();
      if (length < 2 || length > MAX_RESPONSE) {
        throw new Failure();
      }
      var payload = ByteBuffer.allocate(length);
      read(channel, payload);
      if (System.currentTimeMillis() >= deadlineEpochMs) {
        throw new Failure();
      }
      return text(JSON.readTree(payload.array()), id);
    } catch (IOException | RuntimeException failure) {
      throw new Failure();
    } finally {
      synchronized (lock) {
        if (active == channel) {
          active = null;
        }
      }
      close(channel);
    }
  }

  private static String text(JsonNode response, String id) {
    if (!response.isObject()
        || !response.path("version").isIntegralNumber()
        || !response.path("version").canConvertToInt()
        || response.path("version").intValue() != 1
        || !response.path("request_id").isTextual()
        || !id.equals(response.path("request_id").stringValue())) {
      throw new Failure();
    }
    if (response.has("error_code")) {
      if (response.size() != 3
          || !response.path("error_code").isTextual()
          || !ERRORS.contains(response.path("error_code").stringValue())) {
        throw new Failure();
      }
      throw new Failure();
    }
    if (response.size() != 4
        || !response.path("profile").isTextual()
        || !PdfOcrOptions.PIPELINE_PROFILE.equals(response.path("profile").stringValue())
        || !response.path("text").isTextual()) {
      throw new Failure();
    }
    String text = response.path("text").stringValue();
    if (text.codePointCount(0, text.length()) > 1_000_000
        || text.codePoints()
            .anyMatch(
                point ->
                    point >= 0xD800 && point <= 0xDFFF
                        || Character.isISOControl(point)
                            && point != '\n'
                            && point != '\r'
                            && point != '\t')) {
      throw new Failure();
    }
    return text;
  }

  private static void read(SocketChannel channel, ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        throw new IOException();
      }
    }
  }

  private static void write(SocketChannel channel, ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      close(active);
    }
  }

  private static void close(SocketChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // Closing is idempotent; an interrupted blocked call cannot return a parsed page.
      }
    }
  }
}
