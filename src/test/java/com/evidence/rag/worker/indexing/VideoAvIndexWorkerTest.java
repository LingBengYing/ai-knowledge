package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class VideoAvIndexWorkerTest {
  @Test
  void bothCollectionsWithSameActualLeaseStripeCompleteWithoutSelfDeadlock() throws Exception {
    try (var model = new ModelServer();
        var visual = new IndexingTestServer();
        var audio = new IndexingTestServer();
        var projection = new ProjectionServer(visual, audio)) {
      String[] names = collidingCollections(projection.endpoint());
      var request =
          request(model.endpoint(), projection.endpoint(), true, names, Duration.ofSeconds(8));
      projection.bind(request);
      assertNotEquals(
          request.visualProjection().collection(), request.audioProjection().collection());
      assertEquals(stripe(request.visualProjection()), stripe(request.audioProjection()));
      var receipt = VideoAvIndexProtocol.decode(run(request), request);
      assertEquals(5, receipt.entries().size());
      assertEquals(2, receipt.visualReceipt().count());
      assertEquals(3, receipt.audioReceipt().count());
      assertEquals(5, model.inputs.size());
      assertEquals(2, visual.committedUpserts.size());
      assertEquals(3, audio.committedUpserts.size());
      assertArrayEquals(
          request.claim().compilation().windows().getFirst().video().content(),
          model.inputs.get(0));
      assertArrayEquals(
          new byte[32000],
          java.util.Arrays.copyOfRange(model.inputs.get(3), 44, model.inputs.get(3).length));
      assertArrayEquals(
          request.claim().compilation().windows().getLast().audio().wav(), model.inputs.getLast());
    }
  }

  @Test
  void absentAudioHasTaggedReceiptAndMakesNoAudioCollectionOrModelCall() throws Exception {
    try (var model = new ModelServer();
        var visual = new IndexingTestServer();
        var audio = new IndexingTestServer();
        var projection = new ProjectionServer(visual, audio)) {
      var request =
          request(
              model.endpoint(),
              projection.endpoint(),
              false,
              new String[] {"java_video_av_visual", "java_video_av_audio"},
              Duration.ofSeconds(8));
      projection.bind(request);
      var receipt = VideoAvIndexProtocol.decode(run(request), request);
      assertEquals(2, model.inputs.size());
      assertEquals(0, audio.requests.size());
      assertEquals(0, receipt.audioReceipt().count());
      assertEquals(null, receipt.audioReceipt().verified());
    }
  }

  @Test
  void tailProviderFailureAndIncompleteSecondRouteCannotReturnPartialReceipt() throws Exception {
    try (var model = new ModelServer();
        var visual = new IndexingTestServer();
        var audio = new IndexingTestServer();
        var projection = new ProjectionServer(visual, audio)) {
      var request =
          request(
              model.endpoint(),
              projection.endpoint(),
              true,
              new String[] {"java_video_av_visual", "java_video_av_audio"},
              Duration.ofSeconds(8));
      projection.bind(request);
      model.failTail = true;
      byte[] output = run(request);
      assertEquals(
          "video_av_index_failed",
          assertThrows(
                  ProcessVideoAvIndexer.Failure.class,
                  () -> VideoAvIndexProtocol.decode(output, request))
              .code());
      assertEquals(5, model.inputs.size());
      assertEquals(2, audio.committedUpserts.size());
      model.failTail = false;
      audio.failureMode = "missing-verification";
      var retry =
          request(
              model.endpoint(),
              projection.endpoint(),
              true,
              new String[] {"java_video_av_visual", "java_video_av_audio"},
              Duration.ofSeconds(8));
      projection.bind(retry);
      byte[] incomplete = run(retry);
      assertThrows(
          ProcessVideoAvIndexer.Failure.class,
          () -> VideoAvIndexProtocol.decode(incomplete, retry));
    }
  }

  private static byte[] run(VideoAvIndexProtocol.Request request) throws Exception {
    var input = new ByteArrayOutputStream();
    VideoAvIndexProtocol.writeRequest(input, request);
    var output = new ByteArrayOutputStream();
    VideoAvIndexWorker.run(new ByteArrayInputStream(input.toByteArray()), output);
    return output.toByteArray();
  }

  private static VideoAvIndexProtocol.Request request(
      URI model, URI projection, boolean hasAudio, String[] collections, Duration budget) {
    var seed = VideoAvTestFixture.claim(hasAudio);
    var config =
        new GeminiVideoAvEmbeddingModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(model, "video-av", "synthetic-model-credential"),
            "embedding-v1",
            2,
            "decoder-v1",
            Duration.ofSeconds(5),
            65536,
            true);
    var visual = settings(projection, collections[0], config);
    var audio = settings(projection, collections[1], config);
    var targets =
        new VideoAvTargets(
            new IndexTarget(config.revision(), visual.identity(), config.revision(), 2),
            new IndexTarget(config.revision(), audio.identity(), config.revision(), 2));
    return VideoAvIndexProtocol.request(
        config,
        visual,
        audio,
        budget,
        new VideoAvBuildClaim(
            seed.actor(),
            seed.original(),
            targets,
            seed.generationId(),
            seed.analysisModelRevision(),
            seed.decoderRevision(),
            seed.chunkSeconds(),
            seed.compilation(),
            VideoAvProfile.fingerprint(
                targets,
                seed.analysisModelRevision(),
                seed.decoderRevision(),
                seed.chunkSeconds())));
  }

  private static MilvusRestProjection.Settings settings(
      URI endpoint, String collection, GeminiVideoAvEmbeddingModels.Configuration models) {
    return new MilvusRestProjection.Settings(
        endpoint,
        "",
        "default",
        collection,
        "org",
        models.revision(),
        2,
        Duration.ofSeconds(5),
        4 * 1024 * 1024,
        true);
  }

  /** Derive the real lease path hash, including the runtime owner/path and chosen loopback port. */
  private static int stripe(MilvusRestProjection.Settings settings) throws IOException {
    Path tmp = Path.of("/tmp").toRealPath();
    String owner =
        tmp.getFileSystem()
            .getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"))
            .getName();
    String key =
        settings.endpoint().getScheme()
            + "\n"
            + settings.endpoint().getHost()
            + "\n"
            + settings.endpoint().getPort()
            + "\n"
            + settings.database()
            + "\n"
            + settings.collection();
    Path path =
        tmp.resolve("evidence-rag-index-leases-v1-" + sha(owner)).resolve(sha(key) + ".lease");
    return Math.floorMod(path.hashCode(), 64);
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String[] collidingCollections(URI endpoint) throws IOException {
    var buckets = new HashMap<Integer, String>();
    for (int i = 0; i < 1000; i++) {
      String name = "java_video_av_collision_" + i;
      int stripe = stripe(settings(endpoint, name, VideoAvTestFixture.models()));
      String previous = buckets.putIfAbsent(stripe, name);
      if (previous != null) {
        return new String[] {previous, name};
      }
    }
    throw new AssertionError("No bounded stripe collision found");
  }

  private static final class ModelServer implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final HttpServer server;
    final List<byte[]> inputs = new CopyOnWriteArrayList<>();
    volatile boolean failTail;

    ModelServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
              byte[] content =
                  Base64.getDecoder()
                      .decode(
                          body.path("content")
                              .path("parts")
                              .get(0)
                              .path("inlineData")
                              .path("data")
                              .asString());
              inputs.add(content);
              if (failTail && content.length == 46) {
                exchange.sendResponseHeaders(503, -1);
                return;
              }
              byte[] out =
                  JSON.writeValueAsBytes(
                      Map.of("embedding", Map.of("values", List.of(0.25, 0.75))));
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, out.length);
              exchange.getResponseBody().write(out);
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

  /** Each real collection has a separate unchanged projection fixture's storage. */
  private static final class ProjectionServer implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final HttpServer server;
    final HttpClient client = HttpClient.newHttpClient();
    final IndexingTestServer visual, audio;
    volatile List<MilvusRestProjection.Settings> settings = List.of();

    ProjectionServer(IndexingTestServer visual, IndexingTestServer audio) throws IOException {
      this.visual = visual;
      this.audio = audio;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              byte[] input = exchange.getRequestBody().readAllBytes();
              var body = JSON.readTree(input);
              var selected =
                  settings.stream()
                      .filter(
                          s ->
                              s.collection().equals(body.path("collectionName").asString())
                                  && s.database().equals(body.path("dbName").asString()))
                      .findFirst();
              if (selected.isEmpty()) {
                exchange.sendResponseHeaders(400, -1);
                return;
              }
              var current = selected.get();
              var delegate = current == settings.getFirst() ? visual : audio;
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

    void bind(VideoAvIndexProtocol.Request request) {
      settings = List.of(request.visualProjection(), request.audioProjection());
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
