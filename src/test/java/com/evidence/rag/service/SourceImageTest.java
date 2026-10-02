package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static com.evidence.rag.support.PublishedCorpusFixture.physicalIds;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SourceImageTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String TEXT = "上海住宿上限为650元。";
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"png,image/png", "jpeg,image/jpeg"})
  void savedImageCitationReturnsSameOriginalAndHeaderDimensions(String format, String mime)
      throws Exception {
    byte[] original = image(format);
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String traceId = publishImage(fixture, original, format, mime, OWNER);

      var source = fixture.evidence.source(OWNER, traceId, 1);

      assertEquals(TEXT, source.evidence().page().text());
      assertEquals(ModelValues.sha256(original), source.evidence().publication().sourceSha256());
      assertNotNull(source.image());
      assertEquals(mime, source.image().mimeType());
      assertEquals(7, source.image().width());
      assertEquals(11, source.image().height());
      assertArrayEquals(original, source.image().content());
      byte[] callerCopy = source.image().content();
      callerCopy[0] ^= 1;
      assertArrayEquals(original, source.image().content());
    }
  }

  @Test
  void readerMayReadTheirOwnImageCitationButAnotherAuthorizedActorCannot() throws Exception {
    byte[] original = image("png");
    var reader = new Actor(OWNER.workspaceId(), "reader");
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String traceId = publishImage(fixture, original, "png", "image/png", reader);

      assertArrayEquals(original, fixture.evidence.source(reader, traceId, 1).image().content());
      for (Actor other : List.of(OWNER, new Actor("other-org", "reader"))) {
        var failure =
            assertThrows(
                ApplicationException.class, () -> fixture.evidence.source(other, traceId, 1));
        assertEquals(FailureKind.NOT_FOUND, failure.kind());
      }
    }
  }

  @Test
  void withdrawnImageCitationDoesNotReturnTheRetainedOriginal() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String traceId = publishImage(fixture, image("png"), "png", "image/png", OWNER);
      var source = fixture.evidence.source(OWNER, traceId, 1);
      assertNotNull(source.image());
      var store = fixture.authority.store();
      var lifecycle =
          new DocumentLifecycleService(
              store,
              new DocumentLifecycleRepository(store),
              new ManagementRepository(store),
              new IngestionRepository(store),
              new IndexingRepository(store),
              new DocumentPermissionPolicy());

      var removal = lifecycle.removeDocument(OWNER, source.evidence().publication().documentId());

      assertEquals("pending", removal.cleanupStatus());
      var failure =
          assertThrows(
              ApplicationException.class, () -> fixture.evidence.source(OWNER, traceId, 1));
      assertEquals(FailureKind.NOT_FOUND, failure.kind());
    }
  }

  @Test
  void textCitationRemainsATextExcerptWithoutAnImageOriginal() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(OWNER, TEXT);
      var scope =
          fixture.evidence.snapshot(
              OWNER, DocumentSelection.selected(List.of(claim.documentId())), TARGET);
      String textHash = ModelValues.sha256(TEXT.getBytes(StandardCharsets.UTF_8));
      var receipt =
          fixture.evidence.finish(
              scope,
              new TraceDraft(
                  textHash,
                  textHash,
                  "answered",
                  null,
                  TARGET.modelRevision(),
                  "source-image-fixture-v1",
                  "source-image-policy-v1",
                  List.of(
                      new TraceEvidence(
                          1,
                          physicalIds(claim).getFirst(),
                          0,
                          TEXT.codePointCount(0, TEXT.length()),
                          1.0,
                          1.0,
                          List.of(textHash)))),
              () -> AnswerEligibility.ELIGIBLE);

      var source = fixture.evidence.source(OWNER, receipt.traceId(), 1);

      assertEquals(TEXT, source.evidence().page().text());
      assertNull(source.image());
    }
  }

  /** A controlled OCR transcript in real immutable tables; not a claim of executed OCR quality. */
  private static String publishImage(
      PublishedCorpusFixture fixture,
      byte[] original,
      String format,
      String mime,
      Actor answering) {
    var store = fixture.authority.store();
    var management = new ManagementRepository(store);
    var ingestion = new IngestionRepository(store);
    String documentId = "image-document";
    String revisionId = "image-revision";
    String jobId = "image-ingestion";
    String now = "2026-09-08T00:00:00Z";
    String textHash = ModelValues.sha256(TEXT.getBytes(StandardCharsets.UTF_8));
    int length = TEXT.codePointCount(0, TEXT.length());
    store.transaction(
        () -> {
          management.insertDocument(
              OWNER,
              new SyntheticDocument(
                  documentId,
                  "policy." + format,
                  "image",
                  mime,
                  revisionId,
                  ModelValues.sha256(original),
                  original.length),
              now);
          management.insertGrant(documentId, OWNER.principalId(), "owner");
          management.insertGrant(documentId, "reader", "reader");
          ingestion.insertOriginal(
              documentId,
              revisionId,
              "image-ocr-fixture-v1",
              ModelValues.sha256(original),
              original,
              now);
          ingestion.insertJob(jobId, documentId, revisionId, OWNER.principalId(), now);
          ingestion.markProcessing(jobId, "a".repeat(64), now);
          ingestion.insertPage(revisionId, new TextPage(1, TEXT), textHash);
          ingestion.insertSegment(
              "image-segment", revisionId, new TextSegment(0, 1, 0, length, TEXT), textHash);
          ingestion.markParsed(jobId, documentId, revisionId, 1, 1, now);
          return null;
        });
    fixture.authority.indexing().createIndexing(OWNER, documentId, TARGET);
    var claim = fixture.authority.indexing().claimIndexing(OWNER.workspaceId()).orElseThrow();
    var digests = new LinkedHashMap<String, String>();
    physicalIds(claim).forEach(id -> digests.put(id, "a".repeat(64)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            OWNER.workspaceId(), documentId, claim.projectionGenerationId(), digests);
    assertTrue(
        fixture
            .authority
            .indexing()
            .completeIndexing(
                claim,
                digests,
                new VerifiedRevision(
                    TARGET.projectionIdentity(), manifest.sha256(), digests.size())));
    var scope =
        fixture.evidence.snapshot(
            answering, DocumentSelection.selected(List.of(documentId)), TARGET);
    var receipt =
        fixture.evidence.finish(
            scope,
            new TraceDraft(
                ModelValues.sha256("上海住宿上限多少？".getBytes(StandardCharsets.UTF_8)),
                textHash,
                "answered",
                null,
                TARGET.modelRevision(),
                "source-image-fixture-v1",
                "source-image-policy-v1",
                List.of(
                    new TraceEvidence(
                        1, physicalIds(claim).getFirst(), 0, length, 1.0, 1.0, List.of(textHash)))),
            () -> AnswerEligibility.ELIGIBLE);
    assertEquals("answered", receipt.outcome());
    return receipt.traceId();
  }

  private static byte[] image(String format) throws Exception {
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), format, output));
    return output.toByteArray();
  }
}
