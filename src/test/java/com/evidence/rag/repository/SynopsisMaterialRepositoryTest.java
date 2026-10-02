package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.support.SynopsisCorpusFixture;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real sealed SQLite publications, synthetic original media and compilation outputs. */
class SynopsisMaterialRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("embedding", "b".repeat(64), "embedding-v1", 2);
  @TempDir Path directory;

  @Test
  void completeTextIncludesOrderedTailAndReopensExactUnicodePageAndOriginal() {
    try (var fixture = new AuthorityTestContext(directory)) {
      String original = "首😀".repeat(900) + "尾部事实42。";
      var published = text(fixture, original);
      var repository = new SynopsisMaterialRepository(fixture.store());
      var input = tx(fixture, () -> repository.load(OWNER, published));
      assertEquals(published.segmentCount(), input.evidence().size());
      assertTrue(input.evidence().size() > 1);
      assertTrue(
          ((SynopsisEvidence.Text) input.evidence().getLast().content()).text().contains("尾部事实42"));
      for (var item : input.evidence()) {
        var source = tx(fixture, () -> repository.source(OWNER, published, reference(item)));
        var locator = assertInstanceOf(SynopsisSourceMaterial.Page.class, source.locator());
        assertEquals(SynopsisEvidence.Kind.TEXT, source.evidence().kind());
        assertEquals(1, locator.page());
        assertNull(locator.dimensions());
        assertEquals(
            original.substring(
                original.offsetByCodePoints(0, locator.start()),
                original.offsetByCodePoints(0, locator.end())),
            ((SynopsisEvidence.Text) item.content()).text());
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), source.content());
        assertNull(source.frame());
      }
      assertEquals(
          0L,
          tx(fixture, () -> fixture.store().count("SELECT COUNT(*) FROM query_traces"))
              .longValue());
    }
  }

  @Test
  void imageEvidenceUsesOriginalPixelsAndImageOcrUsesExactTextAndIntersectingBoxes() {
    try (var fixture = new AuthorityTestContext(directory)) {
      var repository = new SynopsisMaterialRepository(fixture.store());
      for (boolean ocr : List.of(false, true)) {
        var published = image(fixture, ocr);
        var input = tx(fixture, () -> repository.load(OWNER, published));
        assertEquals(1, input.evidence().size());
        var item = input.evidence().getFirst();
        var source = tx(fixture, () -> repository.source(OWNER, published, reference(item)));
        assertArrayEquals(VideoCompilationFixture.image().content(), source.content());
        if (ocr) {
          assertEquals(SynopsisEvidence.Kind.IMAGE_OCR, item.kind());
          assertEquals("Budget42", ((SynopsisEvidence.Text) item.content()).text());
          var locator = assertInstanceOf(SynopsisSourceMaterial.Page.class, source.locator());
          assertNull(locator.dimensions());
          assertEquals(List.of(new ImageTextRegion(0, 8, 0, 0, 2, 2)), locator.regions());
        } else {
          assertEquals(SynopsisEvidence.Kind.IMAGE, item.kind());
          assertArrayEquals(
              VideoCompilationFixture.image().content(),
              ((SynopsisEvidence.Image) item.content()).image().content());
          assertEquals(
              new ImageDimensions(2, 2),
              assertInstanceOf(SynopsisSourceMaterial.Image.class, source.locator()).dimensions());
        }
      }
    }
  }

  @Test
  void audioKeepsEachPublishedSpanTextAtItsRealTimeAndOmitsSilentProjection() {
    try (var fixture = new AuthorityTestContext(directory)) {
      var published = audio(fixture, List.of("第一事实", "", "远处尾部事实"));
      var repository = new SynopsisMaterialRepository(fixture.store());
      var input = tx(fixture, () -> repository.load(OWNER, published));
      assertEquals(
          List.of("第一事实", "远处尾部事实"),
          input.evidence().stream()
              .map(e -> ((SynopsisEvidence.Text) e.content()).text())
              .toList());
      assertEquals(
          List.of(
              new SynopsisEvidence.TimeRange(0, 1_000_000),
              new SynopsisEvidence.TimeRange(2_000_000, 3_000_000)),
          input.evidence().stream().map(SynopsisEvidence::time).toList());
      for (int i = 0; i < input.evidence().size(); i++) {
        var item = input.evidence().get(i);
        var source = tx(fixture, () -> repository.source(OWNER, published, reference(item)));
        var locator = assertInstanceOf(SynopsisSourceMaterial.Audio.class, source.locator());
        assertEquals(i * 2, locator.spanOrdinal());
        assertEquals(item.time(), locator.time());
        assertEquals(published.sourceSha256(), ModelValues.sha256(source.content()));
        assertNull(source.frame());
      }
    }
  }

  @Test
  void mixedVideoPublishesFramesThenLocalTranscriptsThenOcrWithRealOriginalSources() {
    try (var fixture = new AuthorityTestContext(directory)) {
      var published = video(fixture, 2);
      var repository = new SynopsisMaterialRepository(fixture.store());
      var input = tx(fixture, () -> repository.load(OWNER, published));
      assertEquals(
          List.of(
              SynopsisEvidence.Kind.VIDEO_FRAME,
              SynopsisEvidence.Kind.VIDEO_FRAME,
              SynopsisEvidence.Kind.VIDEO_TRANSCRIPT,
              SynopsisEvidence.Kind.VIDEO_TRANSCRIPT,
              SynopsisEvidence.Kind.VIDEO_OCR),
          input.evidence().stream().map(SynopsisEvidence::kind).toList());
      assertEquals("远处转录", ((SynopsisEvidence.Text) input.evidence().get(3).content()).text());
      assertEquals(
          new SynopsisEvidence.TimeRange(1_000_000, 2_000_000), input.evidence().get(3).time());
      for (var item : input.evidence()) {
        var source = tx(fixture, () -> repository.source(OWNER, published, reference(item)));
        assertArrayEquals(SynopsisCorpusFixture.ORIGINAL_VIDEO, source.content());
        switch (item.kind()) {
          case VIDEO_FRAME -> {
            var locator =
                assertInstanceOf(SynopsisSourceMaterial.VideoFrame.class, source.locator());
            assertEquals(item.time(), locator.time());
            assertArrayEquals(VideoCompilationFixture.image().content(), source.frame().content());
          }
          case VIDEO_TRANSCRIPT -> {
            assertEquals(
                item.time(),
                assertInstanceOf(SynopsisSourceMaterial.VideoTranscript.class, source.locator())
                    .time());
            assertNull(source.frame());
          }
          case VIDEO_OCR -> {
            var locator = assertInstanceOf(SynopsisSourceMaterial.VideoOcr.class, source.locator());
            assertEquals("OCR42", ((SynopsisEvidence.Text) item.content()).text());
            assertEquals(new SynopsisEvidence.TimeRange(0, 200_000), locator.time());
            assertEquals(List.of(new ImageTextRegion(0, 5, 0, 0, 2, 2)), locator.regions());
            assertArrayEquals(VideoCompilationFixture.image().content(), source.frame().content());
          }
          default -> fail("unexpected evidence type");
        }
      }
      assertEquals(
          0L,
          tx(fixture, () -> fixture.store().count("SELECT COUNT(*) FROM query_traces"))
              .longValue());
    }
  }

  @Test
  void completeCountAndAggregateBudgetsFailExplicitlyWithoutChangingIndexedPublication() {
    try (var fixture = new AuthorityTestContext(directory)) {
      var repository = new SynopsisMaterialRepository(fixture.store());
      var tooMany = text(fixture, "文".repeat(80000));
      assertTrue(tooMany.segmentCount() > 64);
      var tooLong = audio(fixture, IntStream.range(0, 17).mapToObj(i -> "字".repeat(4000)).toList());
      var tooManyImages = video(fixture, 9);
      for (var published : List.of(tooMany, tooLong, tooManyImages)) {
        assertEquals(
            "input_capacity_exceeded",
            assertThrows(
                    ApplicationException.class,
                    () -> tx(fixture, () -> repository.load(OWNER, published)))
                .code());
        assertEquals(
            published,
            tx(fixture, () -> repository.publication(OWNER, published.documentId()).orElseThrow()));
      }
    }
  }

  @Test
  void currentPublicationAclAndExactSavedReferenceAreRequiredForMaterialAndSource() {
    try (var fixture = new AuthorityTestContext(directory)) {
      var published = text(fixture, "private original");
      var repository = new SynopsisMaterialRepository(fixture.store());
      var input = tx(fixture, () -> repository.load(OWNER, published));
      var item = input.evidence().getFirst();
      for (var forged :
          List.of(
              new FileSynopsis.Reference("unknown", item.sha256(), item.kind(), null),
              new FileSynopsis.Reference(item.id(), "b".repeat(64), item.kind(), null),
              new FileSynopsis.Reference(
                  item.id(), item.sha256(), SynopsisEvidence.Kind.IMAGE_OCR, null))) {
        assertEquals(
            "not_found",
            assertThrows(
                    ApplicationException.class,
                    () -> tx(fixture, () -> repository.source(OWNER, published, forged)))
                .code());
      }
      var old =
          new PublicationVersion(
              published.documentId(),
              "previous",
              published.sourceRevisionId(),
              published.projectionGenerationId(),
              published.sourceSha256(),
              published.parserRevision(),
              published.target(),
              published.manifestSha256(),
              published.segmentCount());
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class, () -> tx(fixture, () -> repository.load(OWNER, old)))
              .code());
      var outsider = new Actor(OWNER.workspaceId(), "outsider");
      assertTrue(
          tx(fixture, () -> repository.publication(outsider, published.documentId())).isEmpty());
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () -> tx(fixture, () -> repository.source(outsider, published, reference(item))))
              .code());
      tx(
          fixture,
          () -> {
            fixture
                .store()
                .execute("DELETE FROM document_acl WHERE document_id=?", published.documentId());
            return null;
          });
      assertTrue(
          tx(fixture, () -> repository.publication(OWNER, published.documentId())).isEmpty());
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () -> tx(fixture, () -> repository.load(OWNER, published)))
              .code());
    }
  }

  @Test
  void savedSourceSurvivesStoreRestartAndCopiesReturnedBytesAndLocators() {
    PublicationVersion published;
    FileSynopsis.Reference reference;
    try (var fixture = new AuthorityTestContext(directory)) {
      published = image(fixture, true);
      var repository = new SynopsisMaterialRepository(fixture.store());
      reference =
          reference(tx(fixture, () -> repository.load(OWNER, published)).evidence().getFirst());
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new SynopsisMaterialRepository(store);
      var source = store.transaction(() -> repository.source(OWNER, published, reference));
      byte[] bytes = source.content();
      bytes[0] = 0;
      assertEquals(published.sourceSha256(), ModelValues.sha256(source.content()));
      assertTrue(source.toString().endsWith("[redacted]"));
      var locator = assertInstanceOf(SynopsisSourceMaterial.Page.class, source.locator());
      assertThrows(UnsupportedOperationException.class, () -> locator.regions().clear());
    }
  }

  private static PublicationVersion text(AuthorityTestContext fixture, String text) {
    return new SynopsisCorpusFixture(fixture.store(), OWNER, TARGET).text(text);
  }

  private static PublicationVersion image(AuthorityTestContext fixture, boolean ocr) {
    return new SynopsisCorpusFixture(fixture.store(), OWNER, TARGET).image(ocr);
  }

  private static PublicationVersion audio(AuthorityTestContext fixture, List<String> texts) {
    return new SynopsisCorpusFixture(fixture.store(), OWNER, TARGET).audio(texts);
  }

  private static PublicationVersion video(AuthorityTestContext fixture, int count) {
    return new SynopsisCorpusFixture(fixture.store(), OWNER, TARGET).video(count);
  }

  private static FileSynopsis.Reference reference(SynopsisEvidence item) {
    return new FileSynopsis.Reference(item.id(), item.sha256(), item.kind(), item.time());
  }

  private static <T> T tx(AuthorityTestContext fixture, Supplier<T> action) {
    return fixture.store().transaction(action);
  }
}
