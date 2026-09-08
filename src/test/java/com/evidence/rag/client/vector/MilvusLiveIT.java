package com.evidence.rag.client.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection.AuthorizedScope;
import com.evidence.rag.client.vector.RetrievalProjection.Entry;
import com.evidence.rag.client.vector.RetrievalProjection.Query;
import com.evidence.rag.client.vector.RetrievalProjection.RevisionManifest;
import com.evidence.rag.exception.ProjectionException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit real Milvus 2.6 REST integration: run with {@code -Dtest=MilvusLiveIT test}. Requires a
 * new loopback instance and a precreated dedicated {@code java_it_*} database. Synthetic vectors
 * exercise protocol/float32 integrity, not embedding or reranking quality.
 */
class MilvusLiveIT {
  private static final String WORKSPACE = "org-live-it";
  private static final String DOCUMENT = "document-current";
  private static final String SOURCE_REVISION = "source-revision-current";
  private static final String TEXT = "synthetic budget policy approval";
  // These decimal values require float32 canonicalization; integer-only vectors miss that path.
  private static final List<Double> VECTOR = List.of(0.1, -0.2, 0.3, 0.4);

  @Test
  void realProjectionVerifiesExactRowsAndRestrictsBothSearchRoutesToAuthorizedGenerations()
      throws Exception {
    try (var collection = IsolatedCollection.fromEnvironment()) {
      Entry current = entry(WORKSPACE, DOCUMENT, UUID.randomUUID().toString(), "source-current");
      Entry previous = entry(WORKSPACE, DOCUMENT, UUID.randomUUID().toString(), "source-previous");
      Entry otherDocument =
          entry(WORKSPACE, "document-other", UUID.randomUUID().toString(), "source-other");

      try (var writer = new MilvusRestProjection(collection.settings)) {
        collection.initialize(writer);
        writer.upsert(List.of(current, previous, otherDocument));
        for (Entry expected : List.of(current, previous, otherDocument)) {
          var manifest =
              new RevisionManifest(
                  expected.workspaceId(),
                  expected.documentId(),
                  expected.revisionId(),
                  Map.of(expected.segmentId(), RetrievalProjection.entryDigest(expected)));
          var verified = writer.verify(manifest);
          assertEquals(writer.identity(), verified.projectionIdentity());
          assertEquals(manifest.sha256(), verified.manifestSha256());
          assertEquals(1, verified.segmentCount());
        }
      }

      // The production writer is deliberately workspace-bound. This test-only REST fixture
      // places a hostile row in the owned collection after complete verification. Keeping the
      // same document/generation makes workspace filtering the only way to exclude this row.
      Entry foreign =
          entry("org-outside-it", DOCUMENT, current.revisionId(), "source-cross-workspace");
      collection.seedForeignWorkspaceRow(foreign);

      // A fresh client must prepare existing resources, rather than reuse writer readiness.
      try (var reader = new MilvusRestProjection(collection.settings)) {
        reader.prepareSearch();
        assertSingleHybridHit(reader, DOCUMENT, current.revisionId(), current.segmentId());
        assertSingleHybridHit(reader, DOCUMENT, previous.revisionId(), previous.segmentId());
        assertSingleHybridHit(
            reader,
            otherDocument.documentId(),
            otherDocument.revisionId(),
            otherDocument.segmentId());
        assertTrue(reader.search(query(Map.of(), 1)).isEmpty(), "Empty scope must stay empty");
        assertTrue(
            reader.search(query(Map.of(DOCUMENT, SOURCE_REVISION), 1)).isEmpty(),
            "A source revision is not a projection generation");
        assertTrue(
            reader.search(query(Map.of(DOCUMENT, otherDocument.revisionId()), 1)).isEmpty(),
            "A matching generation from a different document is outside the scope");
      }
    }
  }

  @Test
  void fullGenerationVerifies4096ExactEntriesAndRejectsAnUnexpected4097thEntry() throws Exception {
    try (var collection = IsolatedCollection.fromEnvironment();
        var writer = new MilvusRestProjection(collection.settings)) {
      collection.initialize(writer);
      String generation = UUID.randomUUID().toString();
      var digests = new HashMap<String, String>();
      for (int offset = 0; offset < 4096; offset += 16) {
        var batch = new ArrayList<Entry>(16);
        for (int ordinal = offset; ordinal < offset + 16; ordinal++) {
          Entry expected = boundaryEntry(generation, ordinal);
          batch.add(expected);
          digests.put(expected.segmentId(), RetrievalProjection.entryDigest(expected));
        }
        writer.upsert(batch);
      }
      var manifest = new RevisionManifest(WORKSPACE, DOCUMENT, generation, digests);
      assertEquals(4096, manifest.entryDigests().size());
      var verified = writer.verify(manifest);
      assertEquals(writer.identity(), verified.projectionIdentity());
      assertEquals(manifest.sha256(), verified.manifestSha256());
      assertEquals(4096, verified.segmentCount());

      writer.upsert(List.of(boundaryEntry(generation, 4096)));
      var failure = assertThrows(ProjectionException.class, () -> writer.verify(manifest));
      assertEquals("projection_invalid_response", failure.code());
    }
  }

  @Test
  void readOnlyPreparationLeavesAReleasedCollectionUnloadedUntilExplicitWriterInitialization()
      throws Exception {
    try (var collection = IsolatedCollection.fromEnvironment();
        var writer = new MilvusRestProjection(collection.settings)) {
      collection.initialize(writer);
      Entry expected = entry(WORKSPACE, DOCUMENT, UUID.randomUUID().toString(), "source-release");
      writer.upsert(List.of(expected));
      var manifest =
          new RevisionManifest(
              WORKSPACE,
              DOCUMENT,
              expected.revisionId(),
              Map.of(expected.segmentId(), RetrievalProjection.entryDigest(expected)));
      var beforeRelease = writer.verify(manifest);
      assertEquals(writer.identity(), beforeRelease.projectionIdentity());
      assertEquals(manifest.sha256(), beforeRelease.manifestSha256());
      assertEquals(1, beforeRelease.segmentCount());

      collection.release();
      assertEquals("LoadStateNotLoad", collection.loadState());
      try (var reader = new MilvusRestProjection(collection.settings)) {
        reader.prepareSearch();
        assertEquals("LoadStateNotLoad", collection.loadState());
        var failure =
            assertThrows(
                ProjectionException.class,
                () -> reader.search(query(Map.of(DOCUMENT, expected.revisionId()), 2)));
        assertEquals("projection_invalid_response", failure.code());
        assertEquals("LoadStateNotLoad", collection.loadState());

        writer.initialize();
        collection.awaitLoaded();
        assertEquals("LoadStateLoaded", collection.loadState());
        var afterLoad = writer.verify(manifest);
        assertEquals(beforeRelease, afterLoad);
        assertSingleHybridHit(reader, DOCUMENT, expected.revisionId(), expected.segmentId());
      }
    }
  }

  private static Entry boundaryEntry(String generation, int ordinal) {
    return new Entry(
        RetrievalProjection.physicalSegmentId(generation, "source-boundary-" + ordinal),
        WORKSPACE,
        DOCUMENT,
        generation,
        "合成预算第" + ordinal + "条 — synthetic budget policy 🙂🚀",
        List.of(0.1, -0.2, (double) Math.nextUp(0.1f), ordinal % 2 == 0 ? -0.3 : 0.3));
  }

  private static Entry entry(
      String workspace, String document, String generation, String sourceId) {
    return new Entry(
        RetrievalProjection.physicalSegmentId(generation, sourceId),
        workspace,
        document,
        generation,
        TEXT,
        VECTOR);
  }

  private static Query query(Map<String, String> documentGenerations, int limit) {
    return new Query(
        "synthetic budget", VECTOR, new AuthorizedScope(WORKSPACE, documentGenerations), limit);
  }

  private static void assertSingleHybridHit(
      MilvusRestProjection reader, String document, String generation, String physicalId) {
    // Leave room for a second hit so top-K cannot hide the same-vector foreign workspace row.
    var candidates = reader.search(query(Map.of(document, generation), 2));
    assertEquals(
        List.of(physicalId), candidates.stream().map(candidate -> candidate.segmentId()).toList());
    // With one permitted hit, each route contributes rank one to the fixed RRF policy.
    // One successful route alone would contribute only 1/61 and must not pass this check.
    assertEquals(2.0 / 61.0, candidates.getFirst().score(), 1e-12);
  }

  /** Only owns one internally generated collection; never lists or deletes other resources. */
  private static final class IsolatedCollection implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ADMIN_RESPONSE_BYTES = 1_048_576;
    private final String generatedName = "java_it_" + UUID.randomUUID().toString().replace("-", "");
    private final MilvusRestProjection.Settings settings;
    private final HttpClient admin;
    private boolean initializationAttempted;
    private boolean owned;

    private IsolatedCollection(URI endpoint, String token, String database) {
      settings =
          new MilvusRestProjection.Settings(
              endpoint,
              token,
              database,
              generatedName,
              WORKSPACE,
              "synthetic-float32-live-it-v1",
              VECTOR.size(),
              Duration.ofSeconds(60),
              4 * 1_048_576,
              true);
      admin =
          HttpClient.newBuilder()
              .connectTimeout(ADMIN_TIMEOUT)
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
    }

    static IsolatedCollection fromEnvironment() {
      String configuredEndpoint = System.getenv("RAG_MILVUS_IT_ENDPOINT");
      String database = System.getenv("RAG_MILVUS_IT_DATABASE");
      assertNotNull(configuredEndpoint, "Explicit RAG_MILVUS_IT_ENDPOINT is required");
      assertNotNull(database, "Explicit RAG_MILVUS_IT_DATABASE is required");
      assertTrue(
          database.matches("java_it_[A-Za-z0-9_]{1,56}"),
          "Only a precreated dedicated java_it_* database is allowed, never default");
      URI endpoint;
      try {
        endpoint = URI.create(configuredEndpoint);
      } catch (IllegalArgumentException invalid) {
        throw new AssertionError("RAG_MILVUS_IT_ENDPOINT must be a valid dedicated loopback URI");
      }
      assertTrue(
          "http".equals(endpoint.getScheme())
              && endpoint.getHost() != null
              && List.of("127.0.0.1", "[::1]", "::1").contains(endpoint.getHost())
              && endpoint.getPort() >= 1024
              && endpoint.getPort() <= 65535
              && endpoint.getPort() != 19530
              && endpoint.getPort() != 9091
              && endpoint.getUserInfo() == null
              && endpoint.getQuery() == null
              && endpoint.getFragment() == null
              && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath())),
          "Use an explicit new loopback test port, not a default Milvus port or remote endpoint");
      return new IsolatedCollection(
          endpoint, System.getenv().getOrDefault("RAG_MILVUS_IT_TOKEN", ""), database);
    }

    void initialize(MilvusRestProjection writer) throws Exception {
      assertFalse(exists(), "The freshly generated collection must not already exist");
      initializationAttempted = true;
      writer.initialize();
      // A partial or failed initialize never grants cleanup authority.
      owned = true;
      assertTrue(exists(), "Successful initialize must have created the reserved collection");
    }

    void release() throws Exception {
      assertOwned();
      assertTrue(post("collections/release", base()).isObject());
    }

    String loadState() throws Exception {
      return loadState(ADMIN_TIMEOUT);
    }

    private String loadState(Duration timeout) throws Exception {
      assertOwned();
      JsonNode state = post("collections/get_load_state", base(), timeout).path("loadState");
      assertTrue(state.isString(), "Milvus load state must be explicit");
      String value = state.stringValue();
      assertTrue(
          List.of("LoadStateNotLoad", "LoadStateLoading", "LoadStateLoaded").contains(value),
          "Milvus returned an unexpected load state for the owned collection");
      return value;
    }

    void awaitLoaded() throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (true) {
        long remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0, "Explicit collection loading exceeded its state-check budget");
        String state = loadState(Duration.ofNanos(Math.min(remaining, ADMIN_TIMEOUT.toNanos())));
        remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0, "Explicit collection loading exceeded its state-check budget");
        if ("LoadStateLoaded".equals(state)) {
          return;
        }
        assertEquals(
            "LoadStateLoading", state, "Explicit load must not leave the collection released");
        try {
          TimeUnit.NANOSECONDS.sleep(Math.min(remaining, Duration.ofMillis(100).toNanos()));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Milvus IT load-state waiting was interrupted");
        }
      }
    }

    void seedForeignWorkspaceRow(Entry foreign) throws Exception {
      assertOwned();
      assertFalse(WORKSPACE.equals(foreign.workspaceId()));
      var body = base();
      body.put(
          "data",
          List.of(
              Map.of(
                  "id", foreign.segmentId(),
                  "workspace_id", foreign.workspaceId(),
                  "document_id", foreign.documentId(),
                  "revision_id", foreign.revisionId(),
                  "text", foreign.text(),
                  "dense", foreign.vector())));
      JsonNode receipt = post("entities/upsert", body);
      assertEquals(1, receipt.path("upsertCount").intValue());
      assertEquals(JSON.valueToTree(List.of(foreign.segmentId())), receipt.path("upsertIds"));
      var query = base();
      query.put("filter", "id == \"" + foreign.segmentId() + "\"");
      query.put("limit", 2);
      query.put("outputFields", List.of("id", "workspace_id", "document_id", "revision_id"));
      JsonNode rows = post("entities/query", query);
      assertTrue(rows.isArray());
      assertEquals(1, rows.size(), "The excluded workspace row must really be present");
      assertEquals(foreign.segmentId(), rows.get(0).path("id").stringValue());
      assertEquals(foreign.workspaceId(), rows.get(0).path("workspace_id").stringValue());
      assertEquals(foreign.documentId(), rows.get(0).path("document_id").stringValue());
      assertEquals(foreign.revisionId(), rows.get(0).path("revision_id").stringValue());
    }

    private Map<String, Object> base() {
      return new HashMap<>(Map.of("dbName", settings.database(), "collectionName", generatedName));
    }

    private boolean exists() throws Exception {
      JsonNode has = post("collections/has", base()).path("has");
      assertTrue(has.isBoolean(), "Collection presence response must be explicit");
      return has.booleanValue();
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
      return post(path, body, ADMIN_TIMEOUT);
    }

    private JsonNode post(String path, Map<String, Object> body, Duration timeout)
        throws Exception {
      assertEquals(generatedName, body.get("collectionName"));
      assertEquals(settings.database(), body.get("dbName"));
      assertTrue(
          List.of(
                  "collections/has",
                  "collections/drop",
                  "collections/release",
                  "collections/get_load_state",
                  "entities/upsert",
                  "entities/query")
              .contains(path));
      var request =
          HttpRequest.newBuilder(settings.endpoint().resolve("/v2/vectordb/" + path))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .header("Request-Timeout", Long.toString(Math.max(1, timeout.toSeconds())))
              .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      if (!settings.token().isEmpty()) {
        request.header("Authorization", "Bearer " + settings.token());
      }
      var pending = admin.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      try {
        var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        assertEquals(200, response.statusCode(), "Milvus IT HTTP status at " + path);
        assertTrue(response.body().length <= MAX_ADMIN_RESPONSE_BYTES);
        JsonNode parsed = JSON.readTree(response.body());
        assertNotNull(parsed);
        assertTrue(parsed.isObject() && parsed.path("code").isIntegralNumber());
        assertEquals("0", parsed.path("code").toString(), "Milvus IT response code at " + path);
        assertTrue(parsed.has("data"));
        return parsed.path("data");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Milvus IT request interrupted at " + path);
      } catch (ExecutionException | TimeoutException transport) {
        throw new AssertionError("Milvus IT request failed at " + path);
      } finally {
        if (!pending.isDone()) {
          pending.cancel(true);
        }
      }
    }

    private void assertOwned() {
      assertTrue(owned, "Only a collection initialized successfully by this run may be mutated");
      assertTrue(generatedName.matches("java_it_[a-f0-9]{32}"));
      assertEquals(generatedName, settings.collection());
    }

    @Override
    public void close() throws Exception {
      try {
        if (owned) {
          assertOwned();
          post("collections/drop", base());
          assertFalse(exists(), "Only the owned collection should have been removed");
          owned = false;
        } else if (initializationAttempted) {
          throw new AssertionError(
              "Initialize did not complete; no drop attempted for reserved collection "
                  + generatedName);
        }
      } finally {
        admin.shutdownNow();
      }
    }
  }
}
