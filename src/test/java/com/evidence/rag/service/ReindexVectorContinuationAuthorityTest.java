package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedReindexVectors;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReindexVectorContinuationAuthorityTest {
  @TempDir Path directory;

  @Test
  void emptyContinuationIsExplicitAndCannotBeCommittedThroughTheLegacyCompletionSignature() {
    try (var context = context()) {
      String document = context.publish("policy.txt", "Complete saved source.");
      var service = ReindexVectorTestSupport.service(context);
      var claim = ReindexVectorTestSupport.claim(context, service, document);
      var plan = service.reindexVectorPlan(claim).orElseThrow();
      assertTrue(plan.isEmpty());
      assertThrows(
          ApplicationException.class,
          () -> ReindexVectorTestSupport.complete(context, service, claim, null));
      assertEquals("processing", service.indexingStatus(context.owner, claim.jobId()).state());
      assertTrue(
          ReindexVectorTestSupport.complete(
              context, service, claim, new VerifiedReindexVectors(plan, List.of())));
      assertEquals("indexed", service.indexingStatus(context.owner, claim.jobId()).state());
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void aLateReceiptAfterAnEmptySnapshotFailsCurrentPublicationInsteadOfStrandingTheTask() {
    try (var context = context()) {
      String document = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      var old = scope(context, document);
      var service = ReindexVectorTestSupport.service(context);
      var claim = ReindexVectorTestSupport.claim(context, service, document);
      var empty = ReindexVectorTestSupport.verified(service.reindexVectorPlan(claim).orElseThrow());
      ImageVectorEvidenceServiceTest.publishVector(context, old, document);
      assertFalse(service.isIndexingClaimCurrent(claim));
      assertFalse(ReindexVectorTestSupport.complete(context, service, claim, empty));
      assertEquals("failed", service.indexingStatus(context.owner, claim.jobId()).state());
      assertEquals(
          "indexing_output_invalid",
          service.indexingStatus(context.owner, claim.jobId()).errorCode());
      assertEquals(old, scope(context, document));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void receiptProfileMustBeConfiguredForEligibilityCreationClaimAndFinalCommit() {
    try (var context = context()) {
      String document = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      var old = scope(context, document);
      ImageVectorEvidenceServiceTest.publishVector(context, old, document);
      var configured = new AtomicBoolean(false);
      var service = ReindexVectorTestSupport.service(context, (route, target) -> configured.get());
      assertFalse(service.canReindexWithVectors(context.owner, document, context.target));
      assertFalse(
          service.canReindexWithVectors(
              new Actor("org-main", "stranger"), document, context.target));
      assertFalse(service.canReindexWithVectors(context.owner, document, null));
      var refused =
          assertThrows(
              ApplicationException.class,
              () ->
                  service.createReindexing(
                      context.owner,
                      document,
                      old.publications().getFirst().publicationId(),
                      context.target));
      assertEquals("index_configuration_changed", refused.code());
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM indexing_jobs"));
      configured.set(true);
      assertTrue(service.canReindexWithVectors(context.owner, document, context.target));
      var claim = ReindexVectorTestSupport.claim(context, service, document);
      var verification =
          ReindexVectorTestSupport.verified(service.reindexVectorPlan(claim).orElseThrow());
      configured.set(false);
      assertFalse(service.isIndexingClaimCurrent(claim));
      assertFalse(ReindexVectorTestSupport.complete(context, service, claim, verification));
      assertEquals(
          "index_configuration_changed",
          service.indexingStatus(context.owner, claim.jobId()).errorCode());
      assertEquals(old, scope(context, document));
      assertThrows(
          ApplicationException.class,
          () -> service.retryIndexing(context.owner, claim.jobId(), context.target));
    }
  }

  @Test
  void revokedWriterCannotPublishTheFrozenReceiptSetAndOldActiveRemains() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var old = scope(context, audio.documentId());
      AudioVectorQueryFixture.publishVectors(context, old, audio);
      var service = ReindexVectorTestSupport.service(context);
      var claim = ReindexVectorTestSupport.claim(context, service, audio.documentId());
      var verified =
          ReindexVectorTestSupport.verified(service.reindexVectorPlan(claim).orElseThrow());
      context.revoke(audio.documentId());
      assertFalse(ReindexVectorTestSupport.complete(context, service, claim, verified));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM index_publications"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_bindings"));
      assertEquals(
          1,
          context.scalar(
              "SELECT COUNT(*) FROM indexing_jobs WHERE error_code='authorization_changed'"));
    }
  }

  @Test
  void aReceiptProofForAnotherTaskCannotPublishEvenWhenItsSourceSetIsEqual() {
    try (var context = context()) {
      String image = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      ImageVectorEvidenceServiceTest.publishVector(context, scope(context, image), image);
      var service = ReindexVectorTestSupport.service(context);
      var first = ReindexVectorTestSupport.claim(context, service, image);
      var oldProof =
          ReindexVectorTestSupport.verified(service.reindexVectorPlan(first).orElseThrow());
      service.cancelIndexing(context.owner, first.jobId());
      var second = ReindexVectorTestSupport.claim(context, service, image);
      var newPlan = service.reindexVectorPlan(second).orElseThrow();
      assertEquals(oldProof.plan().setSha256(), newPlan.setSha256());
      assertNotEquals(first.jobId(), second.jobId());
      assertThrows(
          ApplicationException.class,
          () -> ReindexVectorTestSupport.complete(context, service, second, oldProof));
      assertTrue(
          ReindexVectorTestSupport.complete(
              context, service, second, ReindexVectorTestSupport.verified(newPlan)));
      assertFalse(ReindexVectorTestSupport.complete(context, service, first, oldProof));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM image_vector_bindings"));
    }
  }

  @Test
  void cancelledReceiptTaskCanRetryWithANewClaimButCannotAcceptTheLateOldClaim() {
    try (var context = context()) {
      var audio =
          AudioTestFixture.publish(context, List.of("Start.", "", AudioVectorQueryFixture.FACT));
      AudioVectorQueryFixture.publishVectors(context, scope(context, audio.documentId()), audio);
      var service = ReindexVectorTestSupport.service(context);
      var first = ReindexVectorTestSupport.claim(context, service, audio.documentId());
      var originalPlan = service.reindexVectorPlan(first).orElseThrow();
      service.cancelIndexing(context.owner, first.jobId());
      service.retryIndexing(context.owner, first.jobId(), context.target);
      var next = service.claimIndexing(context.owner.workspaceId()).orElseThrow();
      assertNotEquals(first.projectionGenerationId(), next.projectionGenerationId());
      assertEquals(originalPlan, service.reindexVectorPlan(next).orElseThrow());
      assertFalse(
          ReindexVectorTestSupport.complete(
              context, service, first, ReindexVectorTestSupport.verified(originalPlan)));
      assertTrue(
          ReindexVectorTestSupport.complete(
              context, service, next, ReindexVectorTestSupport.verified(originalPlan)));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_vector_bindings"));
    }
  }

  @Test
  void failedQueuedProfileIsTerminatedWithoutIssuingAClaim() {
    try (var context = context()) {
      String document = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      var old = scope(context, document);
      ImageVectorEvidenceServiceTest.publishVector(context, old, document);
      var supported = new AtomicBoolean(true);
      var service = ReindexVectorTestSupport.service(context, (route, target) -> supported.get());
      var task =
          service.createReindexing(
              context.owner,
              document,
              old.publications().getFirst().publicationId(),
              context.target);
      supported.set(false);
      assertTrue(service.claimIndexing(context.owner.workspaceId()).isEmpty());
      assertEquals("failed", service.indexingStatus(context.owner, task.taskId()).state());
      assertEquals(
          "index_configuration_changed",
          service.indexingStatus(context.owner, task.taskId()).errorCode());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void managementQualificationCanReuseTheCurrentTransactionAndRejectsWrongTextTarget() {
    try (var context = context()) {
      String document = context.publish("complete.txt", "Saved text.");
      var service = ReindexVectorTestSupport.service(context);
      assertTrue(
          context
              .authority
              .store()
              .transaction(
                  () ->
                      service.canReindexWithVectorsInTransaction(
                          context.owner, document, context.target)));
      var other = new IndexTarget("other", "f".repeat(64), "other", 2);
      assertFalse(service.canReindexWithVectors(context.owner, document, other));
      assertFalse(
          context
              .authority
              .indexing()
              .canReindexWithVectors(context.owner, document, context.target));
      assertTrue(
          context
              .authority
              .store()
              .transaction(
                  () -> new IndexingRepository(context.authority.store()).canReindex(document)));
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
  }

  private static com.evidence.rag.model.domain.EvidenceScope scope(
      AnswerTestContext context, String document) {
    return context.evidence.snapshot(
        context.owner, DocumentSelection.selected(List.of(document)), context.target);
  }
}
