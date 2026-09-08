package com.evidence.rag.client.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusRestProjection.Settings;
import com.evidence.rag.client.vector.RetrievalProjection.AuthorizedScope;
import com.evidence.rag.client.vector.RetrievalProjection.Entry;
import com.evidence.rag.client.vector.RetrievalProjection.Query;
import com.evidence.rag.client.vector.RetrievalProjection.RevisionManifest;
import com.evidence.rag.config.TextAdapterSettings;
import com.evidence.rag.exception.ProjectionException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit {@code -Dtest=MilvusAuthenticationLiveIT test}: real Milvus 2.6.22 authentication and
 * synthetic projection I/O, with zero model requests. Requires a new authenticated loopback
 * instance and a precreated empty {@code java_it_auth_*} database. This is not RBAC, TLS or model
 * acceptance.
 */
class MilvusAuthenticationLiveIT {
  private static final String PREFIX = "RAG_MILVUS_AUTH_IT_";
  private static final String WORKSPACE = "org-auth-it";
  private static final String DOCUMENT = "synthetic-auth-document";
  private static final String TEXT = "synthetic authentication budget policy 合成认证资料 🙂";
  private static final List<Double> VECTOR = List.of(0.1, -0.2, 0.3, 0.4);
  private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(15);
  private static final int MAX_ADMIN_BYTES = 65536;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void validCredentialsSupportProjectionWhileMissingAndIncorrectCredentialsAreRejected() {
    try (var collection = IsolatedCollection.fromEnvironment();
        var writer = new MilvusRestProjection(collection.settings)) {
      collection.assertFresh();
      collection.assertAuthenticationRejected("");
      collection.assertAuthenticationRejected(
          "absent-user-" + UUID.randomUUID() + ":absent-password-" + UUID.randomUUID());
      String validToken = collection.settings.token();
      // Preserve the valid username and password length; change only the final password character.
      String incorrectPasswordToken =
          validToken.substring(0, validToken.length() - 1) + (validToken.endsWith("a") ? "b" : "a");
      collection.assertAuthenticationRejected(incorrectPasswordToken);
      collection.initialize(writer);

      String generation = UUID.randomUUID().toString();
      String physicalId = RetrievalProjection.physicalSegmentId(generation, "synthetic-source");
      var entry = new Entry(physicalId, WORKSPACE, DOCUMENT, generation, TEXT, VECTOR);
      writer.upsert(List.of(entry));
      var manifest =
          new RevisionManifest(
              WORKSPACE,
              DOCUMENT,
              generation,
              Map.of(physicalId, RetrievalProjection.entryDigest(entry)));
      var verified = writer.verify(manifest);
      assertEquals(writer.identity(), verified.projectionIdentity());
      assertEquals(manifest.sha256(), verified.manifestSha256());
      assertEquals(1, verified.segmentCount());

      try (var reader = new MilvusRestProjection(collection.settings)) {
        reader.prepareSearch();
        var candidates =
            reader.search(
                new Query(
                    "synthetic budget",
                    VECTOR,
                    new AuthorizedScope(WORKSPACE, Map.of(DOCUMENT, generation)),
                    2));
        assertEquals(
            List.of(physicalId),
            candidates.stream().map(candidate -> candidate.segmentId()).toList());
        assertEquals(2.0 / 61.0, candidates.getFirst().score(), 1e-12);
      }
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(
        value != null
            && !value.isBlank()
            && value.length() <= 4096
            && value.equals(value.strip())
            && value.codePoints().noneMatch(c -> c < 32 || c == 127),
        "Explicit valid " + name + " is required");
    return value;
  }

  private static final class IsolatedCollection implements AutoCloseable {
    private final Settings settings;
    private final HttpClient admin =
        HttpClient.newBuilder()
            .connectTimeout(ADMIN_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private boolean initializationAttempted;
    private boolean owned;

    private IsolatedCollection(Settings settings) {
      this.settings = settings;
    }

    static IsolatedCollection fromEnvironment() {
      assertTrue(
          "true".equals(required(PREFIX + "ENABLED")),
          "Explicit authentication IT approval is required");
      String token = required(PREFIX + "TOKEN");
      int separator = token.indexOf(':');
      assertTrue(
          separator > 0 && separator < token.length() - 1,
          "Authentication IT credentials require a nonempty username and password");
      String database = required(PREFIX + "DATABASE");
      assertTrue(
          database.matches("java_it_auth_[A-Za-z0-9_]{1,40}"),
          "Use a newly precreated empty java_it_auth_* database");
      URI endpoint;
      try {
        endpoint = URI.create(required(PREFIX + "ENDPOINT"));
      } catch (IllegalArgumentException invalid) {
        throw new AssertionError("The authentication IT endpoint must be a valid URI");
      }
      assertTrue(
          "http".equals(endpoint.getScheme())
              && "127.0.0.1".equals(endpoint.getHost())
              && endpoint.getPort() >= 1024
              && endpoint.getPort() <= 65535
              && !Set.of(19530, 9091).contains(endpoint.getPort())
              && endpoint.getUserInfo() == null
              && endpoint.getQuery() == null
              && endpoint.getFragment() == null
              && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath())),
          "Only a dedicated nondefault loopback authentication endpoint is allowed");

      // The real loader validates all endpoints and derives embedding identity. These model
      // values are deliberately synthetic and non-routable; no model client is constructed.
      String unusedCredential = "synthetic-unused-" + UUID.randomUUID();
      String unusedEndpoint = "https://no-model-requests.invalid/v1";
      var loaded =
          TextAdapterSettings.load(
              Map.ofEntries(
                  Map.entry("RAG_EMBEDDING_BASE_URL", unusedEndpoint),
                  Map.entry("RAG_EMBEDDING_MODEL", "synthetic-unused-embedding"),
                  Map.entry("RAG_EMBEDDING_API_KEY", unusedCredential),
                  Map.entry("RAG_EMBEDDING_DIMENSIONS", Integer.toString(VECTOR.size())),
                  Map.entry("RAG_EMBEDDING_REVISION", "synthetic-auth-it-v1"),
                  Map.entry("RAG_RERANK_BASE_URL", unusedEndpoint),
                  Map.entry("RAG_RERANK_MODEL", "synthetic-unused-rerank"),
                  Map.entry("RAG_RERANK_API_KEY", unusedCredential),
                  Map.entry("RAG_GENERATION_BASE_URL", unusedEndpoint),
                  Map.entry("RAG_GENERATION_MODEL", "synthetic-unused-generation"),
                  Map.entry("RAG_GENERATION_API_KEY", unusedCredential),
                  Map.entry("RAG_MILVUS_ENDPOINT", endpoint.toString()),
                  Map.entry("RAG_MILVUS_TOKEN", token),
                  Map.entry("RAG_MILVUS_DATABASE", database),
                  Map.entry(
                      "RAG_MILVUS_COLLECTION",
                      "java_it_" + UUID.randomUUID().toString().replace("-", "")),
                  Map.entry("RAG_WORKSPACE_ID", WORKSPACE),
                  Map.entry("RAG_TEXT_DEADLINE_MS", "60000"),
                  Map.entry("RAG_TEXT_MAX_RESPONSE_BYTES", "1048576"),
                  Map.entry("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true")));
      return new IsolatedCollection(loaded.projection());
    }

    void assertFresh() {
      JsonNode collections = post("collections/list");
      assertTrue(
          collections.isArray() && collections.isEmpty(),
          "The authentication database must be empty");
      assertFalse(exists(), "The internally generated collection must not already exist");
    }

    void assertAuthenticationRejected(String token) {
      var response = send("collections/has", token);
      // Fixed Milvus v2.6.22 authenticate / ErrNeedAuthenticate: unauthenticated requests are
      // HTTP 401 with code 1800. HTTP 403 is insufficient RBAC, not this authentication proof.
      assertEquals(
          401, response.statusCode(), "Missing or incorrect credentials must be unauthorized");
      JsonNode rejected = parse(response.body());
      assertTrue(
          rejected.isObject()
              && rejected.path("code").isIntegralNumber()
              && "1800".equals(rejected.path("code").toString()),
          "Milvus must return its specific authentication error code");
      // Only this negative fixture bypasses the loader's nonempty-token requirement. All other
      // settings, including the production-derived embedding identity, remain unchanged.
      var rejectedSettings =
          new Settings(
              settings.endpoint(),
              token,
              settings.database(),
              settings.collection(),
              settings.workspaceId(),
              settings.embeddingIdentity(),
              settings.dimension(),
              settings.timeout(),
              settings.maxResponseBytes(),
              settings.allowLoopbackHttp());
      try (var rejectedClient = new MilvusRestProjection(rejectedSettings)) {
        var failure = assertThrows(ProjectionException.class, rejectedClient::prepareSearch);
        assertEquals("projection_remote_failed", failure.code());
        assertNull(failure.getCause());
      }
    }

    void initialize(MilvusRestProjection writer) {
      assertFalse(exists(), "The reserved collection must still be absent before initialization");
      initializationAttempted = true;
      writer.initialize();
      owned = true;
      assertTrue(exists(), "Successful initialization must create the reserved collection");
    }

    private boolean exists() {
      JsonNode has = post("collections/has").path("has");
      assertTrue(has.isBoolean(), "Collection presence must be explicit");
      return has.booleanValue();
    }

    private JsonNode post(String path) {
      var response = send(path, settings.token());
      assertEquals(200, response.statusCode(), "Authenticated Milvus administrative HTTP status");
      JsonNode parsed = parse(response.body());
      assertTrue(
          parsed.isObject()
              && parsed.path("code").isIntegralNumber()
              && "0".equals(parsed.path("code").toString())
              && parsed.has("data"),
          "Authenticated Milvus administrative request failed");
      return parsed.path("data");
    }

    private HttpResponse<byte[]> send(String path, String token) {
      assertTrue(Set.of("collections/list", "collections/has", "collections/drop").contains(path));
      Map<String, String> body =
          "collections/list".equals(path)
              ? Map.of("dbName", settings.database())
              : Map.of("dbName", settings.database(), "collectionName", settings.collection());
      long deadline = System.nanoTime() + ADMIN_TIMEOUT.toNanos();
      CompletableFuture<HttpResponse<byte[]>> pending = null;
      try {
        var request =
            HttpRequest.newBuilder(settings.endpoint().resolve("/v2/vectordb/" + path))
                .timeout(ADMIN_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Request-Timeout", "15")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        if (!token.isEmpty()) {
          request.header("Authorization", "Bearer " + token);
        }
        pending = admin.sendAsync(request.build(), ignored -> new AdministrativeBody());
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new TimeoutException();
        }
        return pending.get(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Milvus authentication IT request was interrupted");
      } catch (ExecutionException | TimeoutException failure) {
        throw new AssertionError("Milvus authentication IT request did not complete");
      } catch (RuntimeException failure) {
        throw new AssertionError("Milvus authentication IT request was invalid");
      } finally {
        if (pending != null && !pending.isDone()) {
          pending.cancel(true);
        }
      }
    }

    @Override
    public void close() {
      try {
        if (owned) {
          assertTrue(settings.collection().matches("java_it_[a-f0-9]{32}"));
          post("collections/drop");
          assertFalse(exists(), "The owned collection must be removed");
          owned = false;
        } else if (initializationAttempted) {
          throw new AssertionError(
              "Initialization was incomplete; reserved collection was not deleted: "
                  + settings.collection());
        }
      } finally {
        admin.shutdownNow();
      }
    }
  }

  private static JsonNode parse(byte[] body) {
    try {
      JsonNode parsed = JSON.readTree(body);
      assertNotNull(parsed, "Milvus authentication IT response must contain JSON");
      return parsed;
    } catch (RuntimeException invalid) {
      throw new AssertionError("Milvus authentication IT response was invalid");
    }
  }

  /** Checks every received buffer before passing the batch to the accumulating subscriber. */
  private static final class AdministrativeBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription upstream;
    private int received;
    private boolean terminated;

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      if (upstream != null) {
        subscription.cancel();
        return;
      }
      upstream = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (terminated) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_ADMIN_BYTES - received) {
          terminated = true;
          upstream.cancel();
          delegate.onError(new IOException("Milvus administrative response exceeds its budget"));
          return;
        }
        received += buffer.remaining();
      }
      delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable failure) {
      if (!terminated) {
        terminated = true;
        delegate.onError(failure);
      }
    }

    @Override
    public void onComplete() {
      if (!terminated) {
        terminated = true;
        delegate.onComplete();
      }
    }
  }
}
