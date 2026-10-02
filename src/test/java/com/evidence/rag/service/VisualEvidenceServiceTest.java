package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.domain.VisualTraceEvidence;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real authority integration; projection receipts are explicit local fixture values. */
class VisualEvidenceServiceTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String VISION = "synthetic-vision-v1";
  private static final String RECALL = "A blue circle is left of a red square.";
  @TempDir Path directory;

  @Test
  void imageCitationSurvivesRestartWithOriginalBytesAndSavedVisualVersions() throws Exception {
    byte[] original = VisualSyntheticFixture.image("png").content();
    String answerId;
    String documentId;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var image = publish(fixture, original);
      documentId = image.documentId();
      var text = fixture.publish(OWNER, "A separate selected text document.");
      var scope =
          fixture.evidence.snapshot(
              OWNER, DocumentSelection.selected(List.of(documentId, text.documentId())), TARGET);

      assertEquals(2, scope.publications().size());
      assertEquals(
          List.of(documentId),
          fixture.evidence.imagePublications(scope).stream()
              .map(item -> item.documentId())
              .toList());
      String physicalId = physical(image);
      var candidates = fixture.evidence.hydrateImages(scope, List.of(physicalId));
      assertEquals(1, candidates.size());
      assertEquals(RECALL, candidates.getFirst().image().recallText());
      var before = fixture.evidence.image(scope, physicalId);
      assertNull(before.trace());
      assertArrayEquals(original, before.original().content());
      var receipt =
          fixture.evidence.finish(scope, draft(physicalId), () -> AnswerEligibility.ELIGIBLE);
      assertEquals("answered", receipt.outcome());
      answerId = receipt.traceId();
    }
    try (var reopened = new PublishedCorpusFixture(directory)) {
      var saved = reopened.evidence.visualSource(OWNER, answerId, 1);
      assertArrayEquals(original, saved.original().content());
      assertEquals(ModelValues.sha256(original), saved.original().sha256());
      assertEquals(documentId, saved.source().publication().documentId());
      assertEquals(640, saved.source().image().width());
      assertEquals(320, saved.source().image().height());
      assertEquals(VISION, saved.trace().visualModelRevision());
      assertEquals("java-visual-assessment-v1", saved.trace().visualPolicyRevision());
      assertEquals(List.of(sha("The circle is blue.")), saved.trace().factSha256());
    }
  }

  private static IndexClaim publish(PublishedCorpusFixture fixture, byte[] original) {
    var store = fixture.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions(VISION));
    var task = ingestion.uploadDocument(OWNER, "shapes.png", "image/png", original);
    var claimed = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(ingestion.completeVisualIngestion(claimed, new ImageRecall(RECALL, VISION)));
    fixture.authority.indexing().createIndexing(OWNER, task.documentId(), TARGET);
    var indexing = fixture.authority.indexing().claimIndexing(OWNER.workspaceId()).orElseThrow();
    var digests = Map.of(physical(indexing), "a".repeat(64));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            OWNER.workspaceId(), task.documentId(), indexing.projectionGenerationId(), digests);
    assertTrue(
        fixture
            .authority
            .indexing()
            .completeIndexing(
                indexing,
                digests,
                new VerifiedRevision(TARGET.projectionIdentity(), manifest.sha256(), 1)));
    return indexing;
  }

  private static String physical(IndexClaim claim) {
    return RetrievalProjection.physicalSegmentId(
        claim.projectionGenerationId(), claim.items().getFirst().evidenceId());
  }

  private static TraceDraft draft(String physicalId) {
    return new TraceDraft(
        sha("What color is the circle?"),
        sha("The circle is blue."),
        "answered",
        null,
        TARGET.modelRevision(),
        "visual-answer-v1",
        "visual-answer-v1",
        List.of(),
        List.of(
            new VisualTraceEvidence(
                1,
                physicalId,
                0.8,
                0.9,
                List.of(sha("The circle is blue.")),
                VISION,
                "java-visual-assessment-v1")));
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
