package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.ImageVectorScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublishedImageEvidence;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.VisualTraceEvidence;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVectorEvidenceServiceTest {
  private static final IndexTarget IMAGE_TARGET =
      new IndexTarget("image-profile-v1", "e".repeat(64), "image-profile-v1", 2);
  @TempDir Path directory;

  @Test
  void completeImageReceiptsAreRequiredAndVectorIdsHydrateOnlyTheirBoundBaseImages() {
    try (var context = context()) {
      String first = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      String second = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      String text = context.publish("selected.txt", "Uncited text remains in the complete scope.");
      var scope = scope(context, first, second, text);
      var a = publishVector(context, scope, first);
      assertEquals(
          "image_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.imageVectorScope(scope, IMAGE_TARGET))
              .code());
      var b = publishVector(context, scope, second);
      var vectorScope = context.evidence.imageVectorScope(scope, IMAGE_TARGET);
      assertEquals(scope, vectorScope.authority());
      assertEquals(3, vectorScope.authority().publications().size());
      assertEquals(2, vectorScope.publications().size());
      assertEquals(
          List.of(b.basePhysicalSegmentId(), a.basePhysicalSegmentId()),
          context
              .evidence
              .hydrateImageVectors(
                  vectorScope, List.of(b.vectorPhysicalSegmentId(), a.vectorPhysicalSegmentId()))
              .stream()
              .map(PublishedImageEvidence::physicalSegmentId)
              .toList());
      assertThrows(
          ApplicationException.class,
          () -> context.evidence.hydrateImageVectors(vectorScope, List.of("foreign-vector")));
      assertThrows(
          ApplicationException.class,
          () ->
              context.evidence.hydrateImageVectors(
                  new ImageVectorScope(scope, IMAGE_TARGET, List.of(a)), List.of()));
      var denied =
          context.evidence.finish(
              scope,
              draft(context, a.basePhysicalSegmentId()),
              () -> AnswerEligibility.ELIGIBLE,
              new ImageVectorScope(scope, IMAGE_TARGET, List.of(a)));
      assertEquals("abstained", denied.outcome());
      assertEquals("image_vector_required", denied.reasonCode());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      var other = new IndexTarget("image-profile-v2", "f".repeat(64), "image-model-v2", 2);
      assertEquals(
          "image_vector_required",
          assertThrows(
                  ApplicationException.class, () -> context.evidence.imageVectorScope(scope, other))
              .code());
      assertEquals(2, context.evidence.imagePublications(scope).size());
    }
  }

  @Test
  void uncitedSelectedDocumentRevocationRejectsCurrentCheckAndFinalImageTrace() {
    try (var context = context()) {
      String image = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      String text = context.publish("uncited.txt", "Complete scope is immutable for this request.");
      var scope = scope(context, image, text);
      var receipt = publishVector(context, scope, image);
      var vectorScope = context.evidence.imageVectorScope(scope, IMAGE_TARGET);
      context.revoke(text);
      assertThrows(
          ApplicationException.class,
          () -> context.evidence.hydrateImageVectors(vectorScope, List.of()));
      var trace =
          context.evidence.finish(
              scope,
              draft(context, receipt.basePhysicalSegmentId()),
              () -> AnswerEligibility.ELIGIBLE,
              vectorScope);
      assertEquals("abstained", trace.outcome());
      assertEquals("scope_changed", trace.reasonCode());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
    }
  }

  @Test
  void forgedManifestCannotQualifyAndSavedReceiptsRemainReadableAfterRestart() {
    String image;
    ImageVectorPublication saved;
    try (var context = context()) {
      image = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      var scope = scope(context, image);
      saved = publishVector(context, scope, image);
      assertEquals(1, context.evidence.imageVectorScope(scope, IMAGE_TARGET).publications().size());
      var changedTarget = new IndexTarget("image-other", "f".repeat(64), "other-model", 2);
      String forgedGeneration = UUID.randomUUID().toString();
      var forged =
          new ImageVectorPublication(
              UUID.randomUUID().toString(),
              saved.basePublication(),
              saved.imageEvidenceId(),
              saved.basePhysicalSegmentId(),
              forgedGeneration,
              RetrievalProjection.physicalSegmentId(forgedGeneration, saved.imageEvidenceId()),
              changedTarget,
              saved.entrySha256(),
              "0".repeat(64),
              Instant.now().toString());
      context
          .authority
          .store()
          .transaction(
              () -> {
                new ImageVectorRepository(context.authority.store()).insert(forged);
                return null;
              });
      assertEquals(
          "image_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.imageVectorScope(scope, changedTarget))
              .code());
      assertThrows(
          ApplicationException.class,
          () ->
              context.evidence.hydrateImageVectors(
                  new ImageVectorScope(scope, changedTarget, List.of(forged)), List.of()));
    }
    try (var context = context()) {
      var scope = scope(context, image);
      assertEquals(
          List.of(saved), context.evidence.imageVectorScope(scope, IMAGE_TARGET).publications());
      assertEquals(
          saved.basePhysicalSegmentId(),
          context
              .evidence
              .hydrateImageVectors(
                  context.evidence.imageVectorScope(scope, IMAGE_TARGET),
                  List.of(saved.vectorPhysicalSegmentId()))
              .getFirst()
              .physicalSegmentId());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void foreignOrAmbiguousReceiptSetsCannotConstructScopeOrCommitAnUnrelatedTrace() {
    try (var context = context()) {
      String first = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      String second = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      var full = scope(context, first, second);
      var firstOnly = scope(context, first);
      var a = publishVector(context, full, first);
      var b = publishVector(context, full, second);
      var otherProfile = new IndexTarget("changed-profile", "f".repeat(64), "changed-profile", 2);
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorScope(full, otherProfile, List.of(a, b)));
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorScope(firstOnly, IMAGE_TARGET, List.of(b)));
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorScope(full, IMAGE_TARGET, List.of(a, a)));
      var sameVectorId =
          new ImageVectorPublication(
              b.id(),
              b.basePublication(),
              b.imageEvidenceId(),
              b.basePhysicalSegmentId(),
              b.vectorGenerationId(),
              a.vectorPhysicalSegmentId(),
              b.target(),
              b.entrySha256(),
              b.manifestSha256(),
              b.createdAt());
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorScope(full, IMAGE_TARGET, List.of(a, sameVectorId)));
      var sameBaseId =
          new ImageVectorPublication(
              b.id(),
              b.basePublication(),
              b.imageEvidenceId(),
              a.basePhysicalSegmentId(),
              b.vectorGenerationId(),
              b.vectorPhysicalSegmentId(),
              b.target(),
              b.entrySha256(),
              b.manifestSha256(),
              b.createdAt());
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorScope(full, IMAGE_TARGET, List.of(a, sameBaseId)));
      var frozen = context.evidence.imageVectorScope(full, IMAGE_TARGET);
      assertThrows(
          ApplicationException.class,
          () ->
              context.evidence.finish(
                  firstOnly,
                  draft(context, a.basePhysicalSegmentId()),
                  () -> AnswerEligibility.ELIGIBLE,
                  frozen));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void selfConsistentManifestForWrongPhysicalGenerationStillCannotQualify() {
    try (var context = context()) {
      String image = QueryAttachmentAnswerFixture.publishImage(context, "vision-v1");
      var scope = scope(context, image);
      var saved = publishVector(context, scope, image);
      var otherProfile = new IndexTarget("changed-profile", "f".repeat(64), "changed-profile", 2);
      String generation = UUID.randomUUID().toString();
      String wrongId =
          RetrievalProjection.physicalSegmentId(
              UUID.randomUUID().toString(), saved.imageEvidenceId());
      var entry =
          new RetrievalProjection.Entry(
              wrongId,
              context.owner.workspaceId(),
              image,
              generation,
              saved.basePublication().sourceSha256(),
              List.of(1.0, 0.0));
      String digest = RetrievalProjection.entryDigest(entry);
      var manifest =
          new RetrievalProjection.RevisionManifest(
              context.owner.workspaceId(), image, generation, Map.of(wrongId, digest));
      var wrong =
          new ImageVectorPublication(
              UUID.randomUUID().toString(),
              saved.basePublication(),
              saved.imageEvidenceId(),
              saved.basePhysicalSegmentId(),
              generation,
              wrongId,
              otherProfile,
              digest,
              manifest.sha256(),
              Instant.now().toString());
      context
          .authority
          .store()
          .transaction(
              () -> {
                new ImageVectorRepository(context.authority.store()).insert(wrong);
                return null;
              });
      assertEquals(
          "image_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.imageVectorScope(scope, otherProfile))
              .code());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
      assertTrue(context.models.calls.isEmpty());
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1);
  }

  private static EvidenceScope scope(AnswerTestContext context, String... ids) {
    return context.evidence.snapshot(
        context.owner, DocumentSelection.selected(List.of(ids)), context.target);
  }

  static ImageVectorPublication publishVector(
      AnswerTestContext context, EvidenceScope scope, String documentId) {
    var publication =
        context.evidence.imagePublications(scope).stream()
            .filter(item -> item.documentId().equals(documentId))
            .findFirst()
            .orElseThrow();
    var images =
        context.evidence.hydrateImages(
            scope,
            context
                .projection
                .data
                .search(
                    new RetrievalProjection.Query(
                        "fixture",
                        List.of(1.0, 0.0),
                        new RetrievalProjection.AuthorizedScope(
                            context.owner.workspaceId(),
                            Map.of(documentId, publication.projectionGenerationId())),
                        64))
                .stream()
                .map(RetrievalProjection.Candidate::segmentId)
                .toList());
    var image = images.getFirst();
    String generation = UUID.randomUUID().toString();
    String vectorId = RetrievalProjection.physicalSegmentId(generation, image.image().id());
    var entry =
        new RetrievalProjection.Entry(
            vectorId,
            context.owner.workspaceId(),
            documentId,
            generation,
            publication.sourceSha256(),
            List.of(1.0, 0.0));
    String digest = RetrievalProjection.entryDigest(entry);
    var manifest =
        new RetrievalProjection.RevisionManifest(
            context.owner.workspaceId(), documentId, generation, Map.of(vectorId, digest));
    var saved =
        new ImageVectorPublication(
            UUID.randomUUID().toString(),
            publication,
            image.image().id(),
            image.physicalSegmentId(),
            generation,
            vectorId,
            IMAGE_TARGET,
            digest,
            manifest.sha256(),
            Instant.now().toString());
    context
        .authority
        .store()
        .transaction(
            () -> {
              new ImageVectorRepository(context.authority.store()).insert(saved);
              return null;
            });
    return saved;
  }

  private static TraceDraft draft(AnswerTestContext context, String physicalId) {
    String hash = ModelValues.sha256("blue circle".getBytes(StandardCharsets.UTF_8));
    return new TraceDraft(
        hash,
        hash,
        "answered",
        null,
        context.target.modelRevision(),
        "visual-v1",
        "visual-v1",
        List.of(),
        List.of(
            new VisualTraceEvidence(
                1, physicalId, .1, .9, List.of(hash), "vision-v1", "java-visual-assessment-v1")));
  }
}
