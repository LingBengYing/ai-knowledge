package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexingResult;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IndexWorkerParentDeathTest {
  @Test
  void forciblyKilledParentCannotLeaveAWriterThatUpsertsAfterRetryPublication() throws Exception {
    try (var server = new IndexingTestServer()) {
      server.failureMode = "block-embedding";
      var claim = server.claim(1);
      Process parent = launchParent();
      ProcessHandle oldWorker = null;
      try {
        try (var input = parent.getOutputStream()) {
          IndexProtocol.writeRequest(
              input,
              IndexProtocol.request(
                  server.settings().models(),
                  server.settings().projection(),
                  Duration.ofSeconds(15),
                  claim));
        }
        assertTrue(
            server.embeddingStarted.await(5, TimeUnit.SECONDS),
            "Old real child must enter embedding");
        oldWorker =
            parent.toHandle().children().filter(ProcessHandle::isAlive).findFirst().orElseThrow();
        assertNotEquals(parent.pid(), oldWorker.pid());
        assertTrue(oldWorker.info().startInstant().isPresent());
        parent.destroyForcibly();
        assertTrue(parent.waitFor(3, TimeUnit.SECONDS));
        server.failureMode = "";
        try (var retry =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(10))) {
          assertEquals(
              1,
              retry.index(server.claim(1, "fixture-retry-generation")).verified().segmentCount());
        }
        long upsertsAtPublication = upserts(server);
        assertEquals(1, upsertsAtPublication);
        server.releaseEmbedding.countDown();
        oldWorker.onExit().get(5, TimeUnit.SECONDS);
        assertFalse(oldWorker.isAlive());
        assertEquals(
            upsertsAtPublication,
            upserts(server),
            "No orphan write may arrive after the retry's verified publication point");
      } finally {
        server.releaseEmbedding.countDown();
        parent.destroyForcibly();
        parent.waitFor(3, TimeUnit.SECONDS);
        if (oldWorker != null && oldWorker.isAlive()) {
          oldWorker.destroyForcibly();
          oldWorker.onExit().get(3, TimeUnit.SECONDS);
        }
      }
    }
  }

  @Test
  void serverAcceptedOldUpsertMayCommitAfterRetryButCannotChangePublishedGeneration()
      throws Exception {
    try (var server = new IndexingTestServer()) {
      server.failureMode = "block-upsert";
      var oldClaim = server.claim(1, "fixture-delayed-old-generation");
      var retryClaim = server.claim(1, "fixture-delayed-new-generation");
      Process parent = launchParent();
      ProcessHandle oldWorker = null;
      try {
        try (var input = parent.getOutputStream()) {
          IndexProtocol.writeRequest(
              input,
              IndexProtocol.request(
                  server.settings().models(),
                  server.settings().projection(),
                  Duration.ofSeconds(15),
                  oldClaim));
        }
        assertTrue(
            server.upsertStarted.await(5, TimeUnit.SECONDS),
            "Old upsert must already be accepted by the remote server");
        oldWorker =
            parent.toHandle().children().filter(ProcessHandle::isAlive).findFirst().orElseThrow();
        parent.destroyForcibly();
        assertTrue(parent.waitFor(3, TimeUnit.SECONDS));
        server.failureMode = "";
        IndexingResult publication;
        try (var retry =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(10))) {
          publication = retry.index(retryClaim);
        }
        oldWorker.onExit().get(3, TimeUnit.SECONDS);
        assertFalse(oldWorker.isAlive());
        assertEquals(1, server.committedUpserts.size());
        String newId =
            RetrievalProjection.physicalSegmentId(
                retryClaim.projectionGenerationId(), retryClaim.items().getFirst().evidenceId());
        String oldId =
            RetrievalProjection.physicalSegmentId(
                oldClaim.projectionGenerationId(), oldClaim.items().getFirst().evidenceId());
        assertNotEquals(oldId, newId);
        assertEquals(java.util.Set.of(newId), publication.entryDigests().keySet());
        server.releaseUpsert.countDown();
        assertTrue(
            server.blockedUpsertCommitted.await(3, TimeUnit.SECONDS),
            "Old accepted HTTP write must actually commit after publication");
        assertEquals(2, server.committedUpserts.size());
        assertEquals(
            oldId,
            server.committedUpserts.getLast().body().path("data").get(0).path("id").asString());
        try (var projection = new MilvusRestProjection(server.settings().projection())) {
          projection.initialize();
          var manifest =
              new RetrievalProjection.RevisionManifest(
                  retryClaim.workspaceId(),
                  retryClaim.documentId(),
                  retryClaim.projectionGenerationId(),
                  publication.entryDigests());
          assertEquals(publication.verified(), projection.verify(manifest));
        }
        assertEquals(
            oldClaim.revisionId(),
            retryClaim.revisionId(),
            "Source revision identity remains unchanged");
        assertEquals(
            oldClaim.items(),
            retryClaim.items(),
            "Source segment identity and locators remain unchanged");
      } finally {
        server.releaseUpsert.countDown();
        parent.destroyForcibly();
        parent.waitFor(3, TimeUnit.SECONDS);
        if (oldWorker != null && oldWorker.isAlive()) {
          oldWorker.destroyForcibly();
          oldWorker.onExit().get(3, TimeUnit.SECONDS);
        }
      }
    }
  }

  private static long upserts(IndexingTestServer server) {
    return server.requests.stream()
        .filter(request -> request.path().endsWith("/entities/upsert"))
        .count();
  }

  private static Process launchParent() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    String absolute =
        String.join(
            File.pathSeparator,
            java.util.Arrays.stream(classpath.split(File.pathSeparator))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString())
                .toList());
    var builder =
        new ProcessBuilder(
                List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m",
                    "-cp",
                    absolute,
                    ParentFixture.class.getName()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    return builder.start();
  }

  public static final class ParentFixture {
    public static void main(String[] args) throws Exception {
      var request = IndexProtocol.readRequest(System.in);
      var claim =
          new IndexClaim(
              "fixture-job",
              request.documentId(),
              request.revisionId(),
              request.workspaceId(),
              1,
              "synthetic-claim-token",
              IndexingTestServer.sha256("synthetic source"),
              com.evidence.rag.tool.parser.TextParser.REVISION,
              request.target(),
              request.items(),
              request.projectionGenerationId());
      try (var indexer =
          new ProcessTextIndexer(request.models(), request.projection(), request.timeout())) {
        indexer.index(claim);
      }
    }
  }
}
