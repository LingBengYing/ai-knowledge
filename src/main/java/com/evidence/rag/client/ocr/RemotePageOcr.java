package com.evidence.rag.client.ocr;

import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.VisualImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One SiliconFlow PaddleOCR-VL-1.5 chat completion per rendered page, sent from the isolated PDF
 * worker with the key of the managed model settings. Several pages may be in flight at once.
 */
public final class RemotePageOcr implements PageOcr {
  static final long PAGE_TIMEOUT_MS = 180_000;
  private static final int MAX_RESPONSE = 4 * 1024 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(2_000_000)
                          .build())
                  .build())
          .build();
  private final URI endpoint;
  private final String key;
  private final long deadlineEpochMs;
  private final HttpClient client;

  public RemotePageOcr(URI provider, String key, long deadlineEpochMs) {
    // Reuse the immutable provider contract (https, or http only on loopback for tests).
    PdfOcrOptions.remote(provider);
    if (key == null || !key.matches("[\\x21-\\x7E]{1,4096}")) {
      throw new IllegalArgumentException("A model API key is required");
    }
    if (deadlineEpochMs <= System.currentTimeMillis()) {
      throw new IllegalArgumentException("Page OCR deadline has expired");
    }
    String base = provider.toString();
    this.endpoint = URI.create(base + (base.endsWith("/") ? "" : "/") + "chat/completions");
    this.key = key;
    this.deadlineEpochMs = deadlineEpochMs;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
  }

  @Override
  public String read(VisualImage image) {
    if (image == null
        || !"image/png".equals(image.mediaType())
        || image.content().length == 0
        || image.content().length > 10 * 1024 * 1024) {
      throw new Failure();
    }
    long now = System.currentTimeMillis();
    long timeout = Math.min(deadlineEpochMs - now, PAGE_TIMEOUT_MS);
    if (timeout <= 0) {
      throw new Failure();
    }
    byte[] body =
        JSON.writeValueAsBytes(
            Map.of(
                "model",
                PdfOcrOptions.REMOTE_MODEL,
                "stream",
                false,
                "messages",
                List.of(
                    Map.of(
                        "role",
                        "user",
                        "content",
                        List.of(
                            Map.of(
                                "type",
                                "image_url",
                                "image_url",
                                Map.of(
                                    "url",
                                    "data:image/png;base64,"
                                        + Base64.getEncoder().encodeToString(image.content()))),
                            Map.of("type", "text", "text", "OCR:"))))));
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(timeout))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new Failure();
    } catch (IOException | RuntimeException unavailable) {
      // Connection failures and page timeouts may succeed on another attempt.
      throw new Failure(System.currentTimeMillis() < deadlineEpochMs);
    }
    byte[] payload;
    try (var input = response.body()) {
      payload = input.readNBytes(MAX_RESPONSE + 1);
    } catch (IOException | RuntimeException unavailable) {
      throw new Failure(System.currentTimeMillis() < deadlineEpochMs);
    }
    int status = response.statusCode();
    if (status != 200) {
      // Rate limits and provider-side errors are transient; bad keys or requests are not.
      throw new Failure(status == 408 || status == 429 || status >= 500);
    }
    if (payload.length > MAX_RESPONSE) {
      throw new Failure();
    }
    return text(payload);
  }

  private static String text(byte[] payload) {
    JsonNode choices;
    try {
      choices = JSON.readTree(payload).path("choices");
    } catch (RuntimeException malformed) {
      throw new Failure(true);
    }
    if (!choices.isArray() || choices.size() != 1) {
      throw new Failure(true);
    }
    var choice = choices.get(0);
    String reason =
        choice.path("finish_reason").isTextual() ? choice.path("finish_reason").stringValue() : "";
    var content = choice.path("message").path("content");
    // "length": the provider's output cap cut a very dense page; keep the recognized text.
    if (!("stop".equals(reason) || "length".equals(reason)) || !content.isTextual()) {
      throw new Failure(true);
    }
    String text = content.stringValue();
    if (text.codePointCount(0, text.length()) > 1_000_000) {
      throw new Failure();
    }
    // Drop control characters the page text contract does not allow instead of failing the page.
    var clean = new StringBuilder(text.length());
    text.codePoints()
        .filter(
            point ->
                !(point >= 0xD800 && point <= 0xDFFF)
                    && (!Character.isISOControl(point)
                        || point == '\n'
                        || point == '\r'
                        || point == '\t'))
        .forEach(clean::appendCodePoint);
    return clean.toString();
  }

  @Override
  public void close() {
    // Aborts every in-flight page request; a blocked read then fails without returning text.
    client.shutdownNow();
  }
}
