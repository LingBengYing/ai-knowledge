package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageVectorBuildClaim;
import com.evidence.rag.model.domain.ImageVectorReceipt;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVectorIndexingServiceTest {
  static final Actor OWNER = new Actor("org", "owner");
  static final IndexTarget IMAGE_TARGET =
      new IndexTarget("image-fixture-v1", "c".repeat(64), "image-model-v1", 2);
  @TempDir Path directory;

  static String publish(PublishedCorpusFixture fixture) {
    var store = fixture.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions("vision-v1"));
    byte[] original;
    try {
      original = VisualSyntheticFixture.image("png").content();
    } catch (IOException failure) {
      throw new AssertionError("Synthetic image fixture could not be encoded", failure);
    }
    var task = ingestion.uploadDocument(OWNER, "shape.png", "image/png", original);
    var claimed = ingestion.claimIngestion("org").orElseThrow();
    assertTrue(
        ingestion.completeVisualIngestion(claimed, new ImageRecall("A blue circle.", "vision-v1")));
    fixture.authority.indexing().createIndexing(OWNER, task.documentId(), TARGET);
    var indexing = fixture.authority.indexing().claimIndexing("org").orElseThrow();
    var entries = Map.of(PublishedCorpusFixture.physicalIds(indexing).getFirst(), "a".repeat(64));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            "org", task.documentId(), indexing.projectionGenerationId(), entries);
    assertTrue(
        fixture
            .authority
            .indexing()
            .completeIndexing(
                indexing,
                entries,
                new VerifiedRevision(TARGET.projectionIdentity(), manifest.sha256(), 1)));
    return task.documentId();
  }

  static ImageVectorReceipt receipt(ImageVectorBuildClaim claim) {
    String id =
        RetrievalProjection.physicalSegmentId(claim.vectorGenerationId(), claim.imageEvidenceId());
    var entry =
        new RetrievalProjection.Entry(
            id,
            "org",
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            claim.basePublication().sourceSha256(),
            List.of(0.25, 0.75));
    String digest = RetrievalProjection.entryDigest(entry);
    var manifest =
        new RetrievalProjection.RevisionManifest(
            "org",
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            Map.of(id, digest));
    return new ImageVectorReceipt(
        id,
        entry.vector(),
        digest,
        new VerifiedRevision(claim.target().projectionIdentity(), manifest.sha256(), 1));
  }

  static ImageVectorIndexingService service(
      PublishedCorpusFixture fixture,
      Duration budget,
      int capacity,
      Supplier<IndexTarget> profiles,
      BiFunction<ImageVectorBuildClaim, Duration, ImageVectorReceipt> build) {
    var store = fixture.authority.store();
    return new ImageVectorIndexingService(
        store,
        new ImageVectorRepository(store),
        new EvidenceRepository(store),
        new ManagementRepository(store),
        new IngestionRepository(store),
        new DocumentPermissionPolicy(),
        TARGET,
        IMAGE_TARGET,
        budget,
        capacity,
        profiles,
        build);
  }

  @Test
  void buildsAnIndependentReceiptAndReopeningIsIdempotentWithoutModels() {
    String doc;
    Object saved;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      doc = publish(fixture);
      var count = new AtomicInteger();
      var service =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                count.incrementAndGet();
                return receipt(claim);
              });
      var before =
          fixture.evidence.snapshot(OWNER, DocumentSelection.selected(List.of(doc)), TARGET);
      assertNull(service.get(OWNER, doc).publication());
      saved = service.build(OWNER, doc).publication();
      assertEquals(saved, service.build(OWNER, doc).publication());
      assertEquals(1, count.get());
      assertEquals(
          before,
          fixture.evidence.snapshot(OWNER, DocumentSelection.selected(List.of(doc)), TARGET));
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var service =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                throw new AssertionError("Stored receipt must not embed again");
              });
      assertEquals(saved, service.get(OWNER, doc).publication());
      assertEquals(saved, service.build(OWNER, doc).publication());
    }
  }

  @Test
  void unknownUnauthorizedAndNonImageInputsNeverInvokeTheWorker() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = publish(fixture);
      var service =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                throw new AssertionError("Rejected input cannot contact models");
              });
      assertThrows(
          ApplicationException.class, () -> service.build(new Actor("other", "owner"), doc));
      assertThrows(ApplicationException.class, () -> service.get(new Actor("org", "reader"), doc));
      assertThrows(ApplicationException.class, () -> service.build(OWNER, "unknown"));
      String text = fixture.publish(OWNER, "Ordinary text.").documentId();
      assertThrows(ApplicationException.class, () -> service.build(OWNER, text));
    }
  }

  @Test
  void corruptedReceiptAndProfileDriftNeverPublishAndRetriesUseNewGenerations() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = publish(fixture);
      var generations = new ArrayList<String>();
      var profile = new AtomicReference<>(IMAGE_TARGET);
      var bad =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              profile::get,
              (claim, budget) -> {
                generations.add(claim.vectorGenerationId());
                var good = receipt(claim);
                return new ImageVectorReceipt(
                    good.physicalSegmentId(), good.vector(), "d".repeat(64), good.verified());
              });
      assertThrows(ApplicationException.class, () -> bad.build(OWNER, doc));
      assertNull(bad.get(OWNER, doc).publication());
      var drift =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              profile::get,
              (claim, budget) -> {
                generations.add(claim.vectorGenerationId());
                profile.set(new IndexTarget("image-v2", "e".repeat(64), "image-model-v2", 2));
                return receipt(claim);
              });
      assertThrows(ApplicationException.class, () -> drift.build(OWNER, doc));
      profile.set(IMAGE_TARGET);
      assertNull(drift.get(OWNER, doc).publication());
      var good =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              profile::get,
              (claim, budget) -> {
                generations.add(claim.vectorGenerationId());
                return receipt(claim);
              });
      assertEquals(doc, good.build(OWNER, doc).basePublication().documentId());
      assertEquals(3, generations.stream().distinct().count());
      assertNotEquals(generations.get(0), generations.get(2));
    }
  }

  @Test
  void readerCanInspectButCannotBuildAndRevokedWriterCannotCommit() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = publish(fixture);
      sql(
          "INSERT INTO document_acl(document_id,principal_id,role) VALUES(?, 'reader', 'reader')",
          doc);
      var service =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                throw new AssertionError("Reader must not build");
              });
      assertNull(service.get(new Actor("org", "reader"), doc).publication());
      assertThrows(
          ApplicationException.class, () -> service.build(new Actor("org", "reader"), doc));
      var revoked =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                sql(
                    "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id='owner'",
                    doc);
                return receipt(claim);
              });
      assertThrows(ApplicationException.class, () -> revoked.build(OWNER, doc));
      assertNull(revoked.get(OWNER, doc).publication());
    }
  }

  @Test
  void removalDuringRemoteWorkPreventsReceiptPublication() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = publish(fixture);
      var base =
          fixture
              .evidence
              .snapshot(OWNER, DocumentSelection.selected(List.of(doc)), TARGET)
              .publications()
              .getFirst();
      var service =
          service(
              fixture,
              Duration.ofSeconds(2),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                sql(
                    "INSERT INTO document_tombstones(document_id,workspace_id,requested_by,requested_at) VALUES(?,'org','owner','2026-10-03T00:00:00Z')",
                    doc);
                return receipt(claim);
              });
      assertThrows(ApplicationException.class, () -> service.build(OWNER, doc));
      var store = fixture.authority.store();
      assertTrue(
          store.transaction(
              () ->
                  new ImageVectorRepository(store)
                      .findPublications("org", List.of(base), IMAGE_TARGET)
                      .isEmpty()));
    }
  }

  @Test
  void deadlineAndCallerInterruptionDoNotSealAReceipt() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = publish(fixture);
      var service =
          service(
              fixture,
              Duration.ofMillis(10),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                try {
                  Thread.sleep(30);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
                return receipt(claim);
              });
      assertEquals(
          "image_vector_timeout",
          assertThrows(ApplicationException.class, () -> service.build(OWNER, doc)).code());
      assertNull(service.get(OWNER, doc).publication());
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "image_vector_interrupted",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, doc)).code());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void capacityAllowsTwoDifferentDocumentsAndRejectsSameDocumentAndThirdRequest() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory);
        var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      String one = publish(fixture), two = publish(fixture), three = publish(fixture);
      var entered = new CountDownLatch(2);
      var release = new CountDownLatch(1);
      var service =
          service(
              fixture,
              Duration.ofSeconds(5),
              2,
              () -> IMAGE_TARGET,
              (claim, budget) -> {
                entered.countDown();
                try {
                  if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out");
                  }
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(failure);
                }
                return receipt(claim);
              });
      var first = threads.submit(() -> service.build(OWNER, one));
      var second = threads.submit(() -> service.build(OWNER, two));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(
            "image_vector_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, one)).code());
        assertEquals(
            "image_vector_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, three)).code());
      } finally {
        release.countDown();
      }
      assertEquals(one, first.get().basePublication().documentId());
      assertEquals(two, second.get().basePublication().documentId());
      assertEquals(three, service.build(OWNER, three).basePublication().documentId());
    }
  }

  private void sql(String statement, String documentId) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var query = connection.prepareStatement(statement)) {
      query.setString(1, documentId);
      query.executeUpdate();
    } catch (java.sql.SQLException failure) {
      throw new AssertionError(failure);
    }
  }
}
