package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFrameTiming;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class VideoAvClientFixture implements AutoCloseable {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final VideoAvEpoch EPOCH = new VideoAvEpoch(200, 1, 8000, 16000);
  static final String DECODER = "av-decoder-v1";
  final AtomicInteger calls = new AtomicInteger();
  final AtomicReference<JsonNode> request = new AtomicReference<>();
  final AtomicReference<String> path = new AtomicReference<>();
  final AtomicReference<String> key = new AtomicReference<>();
  final AtomicReference<String> bearer = new AtomicReference<>();
  final AtomicReference<String> result =
      new AtomicReference<>(
          "{\"complete\":true,\"claims\":[{\"text\":\"A bell moves and rings.\",\"requirement\":\"JOINT\"}]}");
  final AtomicReference<String> prefix = new AtomicReference<>("");
  final AtomicReference<String> status = new AtomicReference<>("completed");
  final AtomicReference<String> model = new AtomicReference<>("av-fixture");
  final AtomicReference<String> object = new AtomicReference<>("");
  final AtomicReference<String> vectorResponse =
      new AtomicReference<>("{\"embedding\":{\"values\":[0.5,-0.25,1]},\"usageMetadata\":{}}");
  final HttpServer server;
  final Endpoint endpoint;
  final GeminiVideoAvModels models;
  final GeminiVideoAvEmbeddingModels embeddings;

  VideoAvClientFixture() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().toString());
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            String response;
            if (path.get().endsWith(":embedContent")) {
              response = vectorResponse.get();
            } else {
              String output =
                  JSON.writeValueAsString(
                      Map.of(
                          "type",
                          "model_output",
                          "content",
                          List.of(Map.of("type", "text", "text", result.get()))));
              response =
                  "{"
                      + object.get()
                      + "\"id\":\"fixture-id\",\"model\":\""
                      + model.get()
                      + "\",\"status\":\""
                      + status.get()
                      + "\",\"steps\":["
                      + prefix.get()
                      + output
                      + "],\"usage\":{}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    endpoint =
        new Endpoint(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "av-fixture",
            "synthetic-key");
    models =
        new GeminiVideoAvModels(
            new GeminiVideoAvModels.Configuration(
                endpoint, "fixture-v1", Duration.ofSeconds(3), 65536, true));
    embeddings =
        new GeminiVideoAvEmbeddingModels(
            new GeminiVideoAvEmbeddingModels.Configuration(
                endpoint, "fixture-v1", 3, DECODER, Duration.ofSeconds(3), 65536, true));
  }

  static VideoAvClip clip(int bytes) {
    byte[] content = new byte[bytes];
    content[bytes - 1] = 7;
    var frames = List.of(new VideoAvFrameTiming(0, 0, 16000, 2, 2, "e".repeat(64)));
    return new VideoAvClip(
        content,
        ModelValues.sha256(content),
        0,
        16000,
        frames,
        VideoAvProfile.framesManifestSha256(frames));
  }

  static AudioWaveform audio() {
    byte[] pcm = new byte[32000];
    pcm[pcm.length - 2] = 3;
    return new AudioWaveform("a".repeat(64), DECODER, 16000, 32000, pcm);
  }

  static VideoAvWindow window() {
    return new VideoAvWindow("b".repeat(64), 0, 16000, 32000, clip(64), audio());
  }

  @Override
  public void close() {
    models.close();
    embeddings.close();
    server.stop(0);
  }
}
