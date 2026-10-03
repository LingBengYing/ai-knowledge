package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.web.HttpProblemMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A saved sidecar must never be stranded by replacing the publication it proves. */
class SavedSourceReindexVectorFenceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void imageReceiptBlocksCreationOrFencesAnAlreadyClaimedRebuild(boolean afterClaim)
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = publishImage(fixture);
      var store = fixture.authority.store();
      var service = fixture.authority.indexing();
      String base = active(store, original.documentId());
      IndexClaim rebuild = null;
      if (afterClaim) {
        service.createReindexing(owner, original.documentId(), base, TARGET);
        rebuild = service.claimIndexing(owner.workspaceId()).orElseThrow();
      }
      var scope =
          fixture.evidence.snapshot(
              owner, DocumentSelection.selected(List.of(original.documentId())), TARGET);
      saveImageReceipt(fixture, original);
      assertTrue(store.transaction(() -> new IndexingRepository(store).hasBaseVectorReceipt(base)));
      if (afterClaim) {
        assertFalse(service.isIndexingClaimCurrent(rebuild));
        assertFalse(complete(service, rebuild));
        assertEquals("failed", service.indexingStatus(owner, rebuild.jobId()).state());
        assertEquals(
            "indexing_output_invalid", service.indexingStatus(owner, rebuild.jobId()).errorCode());
        assertFalse(service.failIndexing(rebuild, "indexing_failed"));
      } else {
        assertConflict(() -> service.createReindexing(owner, original.documentId(), base, TARGET));
        assertEquals(1, count("indexing_jobs"));
      }
      assertEquals(base, active(store, original.documentId()));
      assertEquals(1, count("index_publications"));
      assertEquals(
          scope,
          fixture.evidence.snapshot(
              owner, DocumentSelection.selected(List.of(original.documentId())), TARGET));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void audioReceiptBlocksCreationOrFencesAnAlreadyClaimedRebuild(boolean afterClaim)
      throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      var published = AudioTestFixture.publish(context, List.of("星港项目预算为47万元。", "", "末尾事实是采用太阳能。"));
      var store = context.authority.store();
      var service = context.authority.indexing();
      String base = active(store, published.documentId());
      var selection = DocumentSelection.selected(List.of(published.documentId()));
      var scope = context.evidence.snapshot(context.owner, selection, context.target);
      IndexClaim rebuild = null;
      if (afterClaim) {
        service.createReindexing(context.owner, published.documentId(), base, context.target);
        rebuild = service.claimIndexing(context.owner.workspaceId()).orElseThrow();
      }
      var receipt = AudioVectorQueryFixture.publishVectors(context, scope, published);
      assertEquals(
          List.of(0, 2), receipt.entries().stream().map(entry -> entry.ordinal()).toList());
      assertTrue(store.transaction(() -> new IndexingRepository(store).hasBaseVectorReceipt(base)));
      if (afterClaim) {
        assertFalse(service.isIndexingClaimCurrent(rebuild));
        int modelCallsBeforeCompletion = context.models.calls.size();
        int projectionCallsBeforeCompletion = context.projection.calls.size();
        assertFalse(complete(service, rebuild));
        assertEquals("failed", service.indexingStatus(context.owner, rebuild.jobId()).state());
        assertEquals(
            "indexing_output_invalid",
            service.indexingStatus(context.owner, rebuild.jobId()).errorCode());
        assertFalse(service.failIndexing(rebuild, "indexing_failed"));
        assertEquals(modelCallsBeforeCompletion, context.models.calls.size());
        assertEquals(projectionCallsBeforeCompletion, context.projection.calls.size());
      } else {
        assertConflict(
            () ->
                service.createReindexing(
                    context.owner, published.documentId(), base, context.target));
        assertEquals(1, count("indexing_jobs"));
      }
      assertEquals(base, active(store, published.documentId()));
      assertEquals(1, count("index_publications"));
      assertEquals(scope, context.evidence.snapshot(context.owner, selection, context.target));
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"image", "audio"})
  void aCurrentWorkerCanEndItsAttemptAfterAReceiptInvalidatesItsSourceEligibility(String kind)
      throws Exception {
    if (kind.equals("image")) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        var original = publishImage(fixture);
        var store = fixture.authority.store();
        var service = fixture.authority.indexing();
        String base = active(store, original.documentId());
        service.createReindexing(owner, original.documentId(), base, TARGET);
        var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
        saveImageReceipt(fixture, original);
        assertFalse(service.isIndexingClaimCurrent(claim));
        assertTrue(service.failIndexing(claim, "indexing_failed"));
        assertEquals("failed", service.indexingStatus(owner, claim.jobId()).state());
        assertEquals("indexing_failed", service.indexingStatus(owner, claim.jobId()).errorCode());
        assertFalse(service.failIndexing(claim, "indexing_failed"));
        assertFalse(complete(service, claim));
        assertEquals(base, active(store, original.documentId()));
        assertEquals(1, count("index_publications"));
        assertEquals(1, count("image_vector_publications"));
      }
    } else {
      try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
        var original =
            AudioTestFixture.publish(context, List.of("星港项目预算为47万元。", "", "末尾事实是采用太阳能。"));
        var store = context.authority.store();
        var service = context.authority.indexing();
        String base = active(store, original.documentId());
        var scope =
            context.evidence.snapshot(
                context.owner,
                DocumentSelection.selected(List.of(original.documentId())),
                context.target);
        service.createReindexing(context.owner, original.documentId(), base, context.target);
        var claim = service.claimIndexing(context.owner.workspaceId()).orElseThrow();
        AudioVectorQueryFixture.publishVectors(context, scope, original);
        int modelCallsBeforeFailure = context.models.calls.size();
        int projectionCallsBeforeFailure = context.projection.calls.size();
        assertFalse(service.isIndexingClaimCurrent(claim));
        assertTrue(service.failIndexing(claim, "indexing_failed"));
        assertEquals("failed", service.indexingStatus(context.owner, claim.jobId()).state());
        assertEquals(
            "indexing_failed", service.indexingStatus(context.owner, claim.jobId()).errorCode());
        assertFalse(service.failIndexing(claim, "indexing_failed"));
        assertFalse(complete(service, claim));
        assertEquals(base, active(store, original.documentId()));
        assertEquals(1, count("index_publications"));
        assertEquals(1, count("audio_vector_publications"));
        assertEquals(modelCallsBeforeFailure, context.models.calls.size());
        assertEquals(projectionCallsBeforeFailure, context.projection.calls.size());
        assertTrue(context.models.calls.isEmpty());
      }
    }
  }

  private void saveImageReceipt(PublishedCorpusFixture fixture, IndexClaim original) {
    var store = fixture.authority.store();
    var scope =
        fixture.evidence.snapshot(
            owner, DocumentSelection.selected(List.of(original.documentId())), TARGET);
    var image =
        fixture
            .evidence
            .hydrateImages(scope, PublishedCorpusFixture.physicalIds(original))
            .getFirst();
    String generation = UUID.randomUUID().toString();
    String vectorId = RetrievalProjection.physicalSegmentId(generation, image.image().id());
    var vectorTarget = new IndexTarget("image-fixture", "c".repeat(64), "image-v1", 2);
    var entry =
        new RetrievalProjection.Entry(
            vectorId,
            owner.workspaceId(),
            original.documentId(),
            generation,
            original.sourceSha256(),
            List.of(0.25, 0.75));
    String digest = RetrievalProjection.entryDigest(entry);
    var manifest =
        new RetrievalProjection.RevisionManifest(
            owner.workspaceId(), original.documentId(), generation, Map.of(vectorId, digest));
    var saved =
        new ImageVectorPublication(
            UUID.randomUUID().toString(),
            image.publication(),
            image.image().id(),
            image.physicalSegmentId(),
            generation,
            vectorId,
            vectorTarget,
            digest,
            manifest.sha256(),
            Instant.now().toString());
    store.transaction(
        () -> {
          new ImageVectorRepository(store).insert(saved);
          return null;
        });
  }

  private IndexClaim publishImage(PublishedCorpusFixture fixture) throws Exception {
    var store = fixture.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions("vision-v1"));
    var uploaded =
        ingestion.uploadDocument(
            owner, "shape.png", "image/png", VisualSyntheticFixture.image("png").content());
    var parsed = ingestion.claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeVisualIngestion(parsed, new ImageRecall("A blue circle.", "vision-v1")));
    fixture.authority.indexing().createIndexing(owner, uploaded.documentId(), TARGET);
    var claim = fixture.authority.claimIndexing(owner.workspaceId()).orElseThrow();
    assertTrue(complete(fixture.authority.indexing(), claim));
    return claim;
  }

  private static String active(SqliteAuthorityStore store, String documentId) {
    return store.transaction(
        () -> new IndexingRepository(store).activePublication(documentId).orElseThrow().id());
  }

  private static boolean complete(IndexingService service, IndexClaim claim) {
    var entries = new LinkedHashMap<String, String>();
    for (var item : claim.items()) {
      entries.put(
          RetrievalProjection.physicalSegmentId(claim.projectionGenerationId(), item.evidenceId()),
          "a".repeat(64));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), entries);
    return service.completeIndexing(
        claim,
        entries,
        new VerifiedRevision(
            claim.target().projectionIdentity(), manifest.sha256(), entries.size()));
  }

  private static void assertConflict(Runnable operation) {
    var problem = assertThrows(ApplicationException.class, operation::run);
    assertEquals(409, HttpProblemMapper.status(problem));
    assertEquals("indexing_state_conflict", problem.code());
  }

  private int count(String table) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }
}
