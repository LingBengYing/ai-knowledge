package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundProfile;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SoundIndexWorkerTest {
  @Test
  void completeDescribeAndOriginalPcmEmbeddingIncludeSilenceAndTailBeforeWholeVerify()
      throws Exception {
    try (var model = new ModelServer();
        var projection = new IndexingTestServer();
        var soundProjection = new ProjectionServer(projection)) {
      var request = request(model.endpoint(), soundProjection.endpoint());
      soundProjection.bind(request.projection());
      var input = new ByteArrayOutputStream();
      SoundIndexProtocol.writeRequest(input, request);
      var output = new ByteArrayOutputStream();
      // Public run uses the existing non-halting lifetime Interface, not isolated main in JUnit.
      SoundIndexWorker.run(new ByteArrayInputStream(input.toByteArray()), output);
      var receipt = SoundIndexProtocol.decode(output.toByteArray(), request);
      assertEquals(3, receipt.entries().size());
      assertEquals("", receipt.entries().get(1).recallText());
      assertEquals(3, model.descriptions.size());
      assertEquals(3, model.embeddings.size());
      for (int i = 0; i < 3; i++) {
        assertArrayEquals(
            request.claim().spans().get(i).waveform().wav(), model.descriptions.get(i));
        assertArrayEquals(request.claim().spans().get(i).waveform().wav(), model.embeddings.get(i));
      }
      assertEquals(3, projection.committedUpserts.size());
      assertEquals(
          request.claim().spans().getLast().waveform().pcmSha256(),
          projection.committedUpserts.getLast().body().path("data").get(0).path("text").asString());
      assertFalse(Thread.currentThread().isInterrupted());
    }
  }

  @Test
  void tailDescriptionFailureAndIncompleteProjectionNeverReturnPartialReceipt() throws Exception {
    try (var model = new ModelServer();
        var projection = new IndexingTestServer();
        var soundProjection = new ProjectionServer(projection)) {
      var request = request(model.endpoint(), soundProjection.endpoint());
      soundProjection.bind(request.projection());
      model.failTail = true;
      var output = run(request);
      assertEquals(
          "sound_index_unavailable",
          assertThrows(
                  ProcessSoundIndexer.Failure.class,
                  () -> SoundIndexProtocol.decode(output, request))
              .code());
      assertEquals(3, model.descriptions.size());
      assertEquals(2, model.embeddings.size());
      model.failTail = false;
      projection.failureMode = "missing-verification";
      var retry = request(model.endpoint(), soundProjection.endpoint());
      byte[] incomplete = run(retry);
      assertThrows(
          ProcessSoundIndexer.Failure.class, () -> SoundIndexProtocol.decode(incomplete, retry));
    }
  }

  private static byte[] run(SoundIndexProtocol.Request request) throws Exception {
    var input = new ByteArrayOutputStream();
    SoundIndexProtocol.writeRequest(input, request);
    var output = new ByteArrayOutputStream();
    SoundIndexWorker.run(new ByteArrayInputStream(input.toByteArray()), output);
    return output.toByteArray();
  }

  private static SoundIndexProtocol.Request request(URI model, URI projection) {
    var seed = SoundIndexProtocolTest.request();
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(model, "sound", "synthetic-model-credential");
    var models =
        new GeminiSoundModels.Configuration(
            endpoint, "sound-v1", Duration.ofSeconds(5), 65536, true);
    var embeddings =
        new GeminiSoundEmbeddingModels.Configuration(
            endpoint, "embedding-v1", 2, "decoder-v1", Duration.ofSeconds(5), 65536, true);
    var settings =
        new MilvusRestProjection.Settings(
            projection,
            "",
            "default",
            "java_sound_worker",
            "org",
            embeddings.revision(),
            2,
            Duration.ofSeconds(5),
            4 * 1024 * 1024,
            true);
    var target =
        new IndexTarget(embeddings.revision(), settings.identity(), embeddings.revision(), 2);
    var claim = seed.claim();
    return SoundIndexProtocol.request(
        models,
        embeddings,
        settings,
        Duration.ofSeconds(15),
        new SoundBuildClaim(
            claim.actor(),
            claim.original(),
            target,
            claim.generationId(),
            models.revision(),
            claim.decoderRevision(),
            1,
            claim.spans(),
            SoundProfile.fingerprint(target, models.revision(), claim.decoderRevision(), 1)));
  }

  private static final class ModelServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    final HttpServer server;
    final List<byte[]> descriptions = new CopyOnWriteArrayList<>();
    final List<byte[]> embeddings = new CopyOnWriteArrayList<>();
    volatile boolean failTail;

    ModelServer() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
              String path = exchange.getRequestURI().getPath();
              Object response;
              if (path.equals("/v1beta/interactions")) {
                byte[] wav =
                    Base64.getDecoder().decode(body.path("input").get(1).path("data").asString());
                descriptions.add(wav);
                String recall = wav[44] == 0 ? "" : "tone";
                String status = failTail && wav.length < 100 ? "incomplete" : "completed";
                response =
                    Map.of(
                        "object",
                        "interaction",
                        "id",
                        "synthetic-interaction",
                        "model",
                        "sound",
                        "status",
                        status,
                        "steps",
                        List.of(
                            Map.of(
                                "type",
                                "model_output",
                                "content",
                                List.of(
                                    Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        JSON.writeValueAsString(Map.of("recall_text", recall)))))));
              } else if (path.equals("/v1beta/models/sound:embedContent")) {
                embeddings.add(
                    Base64.getDecoder()
                        .decode(
                            body.path("content")
                                .path("parts")
                                .get(0)
                                .path("inlineData")
                                .path("data")
                                .asString()));
                response = Map.of("embedding", Map.of("values", List.of(0.1, 0.9)));
              } else {
                exchange.sendResponseHeaders(404, -1);
                return;
              }
              byte[] bytes = JSON.writeValueAsBytes(response);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void close() {
      server.stop(0);
    }
  }

  /** Bind the unchanged text fixture's schema response to this sound collection/profile. */
  private static final class ProjectionServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private volatile MilvusRestProjection.Settings settings;

    ProjectionServer(IndexingTestServer delegate) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              byte[] input = exchange.getRequestBody().readAllBytes();
              var request = JSON.readTree(input);
              var current = settings;
              if (current == null
                  || !request.path("collectionName").asString().equals(current.collection())
                  || !request.path("dbName").asString().equals(current.database())) {
                exchange.sendResponseHeaders(400, -1);
                return;
              }
              var response =
                  client.send(
                      HttpRequest.newBuilder(delegate.endpoint().resolve(exchange.getRequestURI()))
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofByteArray(input))
                          .build(),
                      HttpResponse.BodyHandlers.ofByteArray());
              byte[] output = response.body();
              if (exchange.getRequestURI().getPath().endsWith("/collections/describe")) {
                var envelope = (ObjectNode) JSON.readTree(output);
                var schema = (ObjectNode) envelope.path("data");
                schema.put("collectionName", current.collection());
                schema.put(
                    "description",
                    "evidence-rag-java-text-v1;workspace="
                        + current.workspaceId()
                        + ";embedding="
                        + current.embeddingIdentity()
                        + ";dim="
                        + current.dimension());
                output = JSON.writeValueAsBytes(envelope);
              }
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(response.statusCode(), output.length);
              exchange.getResponseBody().write(output);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      server.start();
    }

    void bind(MilvusRestProjection.Settings value) {
      settings = value;
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void close() {
      server.stop(0);
      client.close();
    }
  }
}
