package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.config.AudioEmbeddingSettings;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.ReindexVectorVerifier;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real loopback reads prove complete origin generation integrity, without any media model calls.
 */
class ReindexVectorVerifierHttpTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"changed", "missing-tail", "duplicate-id"})
  void fullAudioVerificationRejectsRemoteMutationIncludingTheUncitedTail(String corruption)
      throws Exception {
    var fixture = new ReindexVectorContinuationHttpFixture(directory);
    try (var remote = new ReindexVectorContinuationHttpFixture.Remote();
        var http = HttpClient.newHttpClient();
        var app = fixture.start(remote)) {
      var seed = fixture.seed(app, http, "audio");
      var service = app.getBean(IndexingService.class);
      var plan = plan(service, seed);
      assertEquals(2, plan.audios().getFirst().origin().entries().size());
      String collection = "java_audio_continuation_fixture";
      switch (corruption) {
        case "changed" -> remote.corruptCollection = collection;
        case "missing-tail" -> remote.omitCollection = collection;
        case "duplicate-id" -> remote.duplicateCollection = collection;
        default -> throw new AssertionError(corruption);
      }
      int before = remote.requests.size();
      var failure =
          assertThrows(
              ProcessTextIndexer.Failure.class,
              () -> app.getBean(ReindexVectorVerifier.class).verify(plan, Duration.ofSeconds(5)));
      assertEquals("indexing_output_invalid", failure.code());
      assertTrue(remote.requests.size() > before);
      assertTrue(
          remote.requests.subList(before, remote.requests.size()).stream()
              .noneMatch(
                  call ->
                      call.path().endsWith("upsert")
                          || call.path().endsWith("create")
                          || call.path().endsWith("load")
                          || call.path().contains("embedding")
                          || call.path().endsWith(":embedContent")));
      remote.corruptCollection = "";
      remote.omitCollection = "";
      remote.duplicateCollection = "";
      var verified = app.getBean(ReindexVectorVerifier.class).verify(plan, Duration.ofSeconds(5));
      assertEquals(plan, verified.plan());
      assertEquals(2, verified.receipts().getFirst().verified().segmentCount());
    }
  }

  @Test
  void decoderRotationChangesTheExactTargetAndRefusesBeforeTheFirstRemoteRead() throws Exception {
    var fixture = new ReindexVectorContinuationHttpFixture(directory);
    try (var remote = new ReindexVectorContinuationHttpFixture.Remote();
        var http = HttpClient.newHttpClient();
        var app = fixture.start(remote)) {
      var seed = fixture.seed(app, http, "audio");
      var plan = plan(app.getBean(IndexingService.class), seed);
      var settings = app.getBean(AudioEmbeddingSettings.class);
      var original = settings.models();
      var rotated =
          new GeminiAudioEmbeddingModels.Configuration(
              original.endpoint(),
              original.modelRevision(),
              original.dimensions(),
              "changed-decoder-v2",
              original.deadline(),
              original.maxResponseBytes(),
              original.allowLoopbackHttp());
      int before = remote.requests.size();
      try (var model = new GeminiAudioEmbeddingModels(rotated)) {
        var p = settings.projection();
        var projection =
            new MilvusRestProjection.Settings(
                p.endpoint(),
                p.token(),
                p.database(),
                p.collection(),
                p.workspaceId(),
                model.revision(),
                p.dimension(),
                p.timeout(),
                p.maxResponseBytes(),
                p.allowLoopbackHttp());
        var target =
            new IndexTarget(
                model.revision(), projection.identity(), model.revision(), model.dimensions());
        var verifier =
            new ReindexVectorVerifier(Map.of("audio", target), Map.of("audio", projection));
        assertEquals(
            "index_configuration_changed",
            assertThrows(
                    ProcessTextIndexer.Failure.class,
                    () -> verifier.verify(plan, Duration.ofSeconds(5)))
                .code());
        assertEquals(before, remote.requests.size());
      }
    }
  }

  @Test
  void totalDeadlineAndInterruptionReleaseTheCollectionLeaseForTheNextVerification()
      throws Exception {
    var fixture = new ReindexVectorContinuationHttpFixture(directory);
    try (var remote = new ReindexVectorContinuationHttpFixture.Remote();
        var http = HttpClient.newHttpClient();
        var app = fixture.start(remote)) {
      var plan = plan(app.getBean(IndexingService.class), fixture.seed(app, http, "audio"));
      var verifier = app.getBean(ReindexVectorVerifier.class);
      remote.delayMillis = 300;
      try {
        var failure =
            assertThrows(
                ProcessTextIndexer.Failure.class,
                () -> verifier.verify(plan, Duration.ofMillis(25)));
        assertEquals("indexing_timeout", failure.code());
      } finally {
        Thread.interrupted();
        remote.delayMillis = 0;
      }
      int before = remote.requests.size();
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "worker_interrupted",
            assertThrows(
                    ProcessTextIndexer.Failure.class,
                    () -> verifier.verify(plan, Duration.ofSeconds(5)))
                .code());
      } finally {
        Thread.interrupted();
      }
      assertEquals(before, remote.requests.size());
      assertEquals(1, verifier.verify(plan, Duration.ofSeconds(5)).receipts().size());
    }
  }

  private static ReindexVectorPlan plan(
      IndexingService service, ReindexVectorContinuationHttpFixture.Seed seed) {
    var actor = new com.evidence.rag.model.domain.Actor("org-main", "owner");
    service.createReindexing(
        actor, seed.document(), seed.publication().publicationId(), seed.publication().target());
    var claim = service.claimIndexing(actor.workspaceId()).orElseThrow();
    return service.reindexVectorPlan(claim).orElseThrow();
  }
}
