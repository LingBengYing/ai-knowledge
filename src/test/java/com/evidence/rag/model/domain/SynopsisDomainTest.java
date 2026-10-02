package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class SynopsisDomainTest {
  private static final String SHA = "a".repeat(64);
  private static final SynopsisEvidence.TimeRange TIME = new SynopsisEvidence.TimeRange(1, 20);

  @Test
  void evidenceKeepsExactUnicodeContextAndHashesOriginalContent() {
    String text = "首行😀\n第二行\t尾部\r\n";
    var source = text("source", SynopsisEvidence.Kind.TEXT, text, null);
    assertEquals(text, ((SynopsisEvidence.Text) source.content()).text());
    assertEquals(ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)), source.sha256());
    var image = image("image", SynopsisEvidence.Kind.IMAGE, "image/png", new byte[] {1, 2}, null);
    assertEquals(ModelValues.sha256(new byte[] {1, 2}), image.sha256());
    assertEquals("😀".repeat(64000), new SynopsisEvidence.Text("😀".repeat(64000)).text());
  }

  @Test
  void everyModalityRequiresTheMatchingOriginalContentAndServerTime() {
    for (var kind : SynopsisEvidence.Kind.values()) {
      boolean temporal =
          kind == SynopsisEvidence.Kind.AUDIO_TRANSCRIPT
              || kind == SynopsisEvidence.Kind.VIDEO_FRAME
              || kind == SynopsisEvidence.Kind.VIDEO_TRANSCRIPT
              || kind == SynopsisEvidence.Kind.VIDEO_OCR
              || kind == SynopsisEvidence.Kind.VIDEO_SUBTITLE;
      boolean pixels =
          kind == SynopsisEvidence.Kind.IMAGE || kind == SynopsisEvidence.Kind.VIDEO_FRAME;
      SynopsisEvidence.Content content =
          pixels
              ? new SynopsisEvidence.Image(new VisualImage("image/png", new byte[] {1}))
              : new SynopsisEvidence.Text("实际文字");
      assertEquals(
          kind, new SynopsisEvidence("source", kind, content, temporal ? TIME : null).kind());
      invalid(() -> new SynopsisEvidence("source", kind, content, temporal ? null : TIME));
      SynopsisEvidence.Content wrong =
          pixels
              ? new SynopsisEvidence.Text("caption cannot be original pixels")
              : new SynopsisEvidence.Image(new VisualImage("image/png", new byte[] {1}));
      invalid(() -> new SynopsisEvidence("source", kind, wrong, temporal ? TIME : null));
    }
  }

  @Test
  void malformedEvidenceAndNonUnicodeOrControlTextAreRejected() {
    for (String text :
        Arrays.asList(
            null, "", " \n\t", "a\u0000b", "a\u0001b", "\ud800", "\udfff", "a".repeat(64001))) {
      invalid(() -> new SynopsisEvidence.Text(text));
    }
    invalid(() -> new SynopsisEvidence.Image(null));
    invalid(
        () ->
            new SynopsisEvidence(
                null, SynopsisEvidence.Kind.TEXT, new SynopsisEvidence.Text("text"), null));
    invalid(() -> new SynopsisEvidence("source", null, new SynopsisEvidence.Text("text"), null));
    invalid(() -> new SynopsisEvidence("source", SynopsisEvidence.Kind.TEXT, null, null));
    invalid(() -> new SynopsisEvidence.TimeRange(-1, 20));
    invalid(() -> new SynopsisEvidence.TimeRange(2, 2));
    invalid(() -> new SynopsisEvidence.TimeRange(3, 2));
    assertEquals(Long.MAX_VALUE, new SynopsisEvidence.TimeRange(0, Long.MAX_VALUE).endUs());
  }

  @Test
  void completeInputCopiesEvidenceAndOriginalPixelsDefensively() {
    byte[] pixels = {1, 2};
    var item = image("image", SynopsisEvidence.Kind.IMAGE, "image/png", pixels, null);
    var evidence = new ArrayList<>(List.of(item));
    var input = new SynopsisInput(publication(1), evidence);
    String before = input.fingerprint();
    evidence.clear();
    pixels[0] = 9;
    ((SynopsisEvidence.Image) item.content()).image().content()[0] = 8;
    assertEquals(1, input.evidence().size());
    assertEquals(1, ((SynopsisEvidence.Image) item.content()).image().content()[0]);
    assertEquals(before, input.fingerprint());
    assertThrows(UnsupportedOperationException.class, () -> input.evidence().clear());
  }

  @Test
  void inputRejectsMissingDuplicateNullAndExcessEvidenceInsteadOfTruncating() {
    var item = text("source", SynopsisEvidence.Kind.TEXT, "text", null);
    invalid(() -> new SynopsisInput(null, List.of(item)));
    invalid(() -> new SynopsisInput(publication(1), null));
    invalid(() -> new SynopsisInput(publication(1), List.of()));
    invalid(() -> new SynopsisInput(publication(2), List.of(item)));
    invalid(() -> new SynopsisInput(publication(2), List.of(item, item)));
    invalid(() -> new SynopsisInput(publication(1), Arrays.asList((SynopsisEvidence) null)));
    var maximum =
        IntStream.range(0, 64)
            .mapToObj(i -> text("source" + i, SynopsisEvidence.Kind.TEXT, "x", null))
            .toList();
    assertEquals(64, new SynopsisInput(publication(64), maximum).evidence().size());
    var excess = new ArrayList<>(maximum);
    excess.add(text("tail", SynopsisEvidence.Kind.TEXT, "untruncated", null));
    invalid(() -> new SynopsisInput(publication(65), excess));
  }

  @Test
  void aggregateUnicodeAndImageBudgetsApplyToTheEntireInputIncludingItsTail() {
    var first = text("first", SynopsisEvidence.Kind.TEXT, "😀".repeat(32000), null);
    var last = text("last", SynopsisEvidence.Kind.TEXT, "尾".repeat(32000), null);
    assertEquals(2, new SynopsisInput(publication(2), List.of(first, last)).evidence().size());
    var overflow = text("last", SynopsisEvidence.Kind.TEXT, "尾".repeat(32001), null);
    invalid(() -> new SynopsisInput(publication(2), List.of(first, overflow)));
    var images =
        IntStream.range(0, 8)
            .mapToObj(
                i ->
                    image(
                        "image" + i,
                        SynopsisEvidence.Kind.IMAGE,
                        "image/png",
                        new byte[] {1},
                        null))
            .toList();
    assertEquals(8, new SynopsisInput(publication(8), images).evidence().size());
    var tooMany = new ArrayList<>(images);
    tooMany.add(image("tail", SynopsisEvidence.Kind.IMAGE, "image/png", new byte[] {1}, null));
    invalid(() -> new SynopsisInput(publication(9), tooMany));
    var full =
        image("full", SynopsisEvidence.Kind.IMAGE, "image/png", new byte[8 * 1024 * 1024], null);
    assertEquals(1, new SynopsisInput(publication(1), List.of(full)).evidence().size());
    invalid(() -> new SynopsisInput(publication(2), List.of(full, images.getFirst())));
  }

  @Test
  void fingerprintBindsEveryPublicationAndIndexVersionField() {
    var evidence = List.of(text("source", SynopsisEvidence.Kind.TEXT, "text", null));
    var original = publication(1);
    String fingerprint = new SynopsisInput(original, evidence).fingerprint();
    assertTrue(fingerprint.matches("[a-f0-9]{64}"));
    var variants =
        List.of(
            version(
                "other",
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                original.target(),
                original.manifestSha256()),
            version(
                original.documentId(),
                "other",
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                original.target(),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                "other",
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                original.target(),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                "other",
                original.sourceSha256(),
                original.parserRevision(),
                original.target(),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                "b".repeat(64),
                original.parserRevision(),
                original.target(),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                "other",
                original.target(),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                new IndexTarget("other", "projection", "embedding-v1", 3),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                new IndexTarget("embedding", "other", "embedding-v1", 3),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                new IndexTarget("embedding", "projection", "embedding-v2", 3),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                new IndexTarget("embedding", "projection", "embedding-v1", 4),
                original.manifestSha256()),
            version(
                original.documentId(),
                original.publicationId(),
                original.sourceRevisionId(),
                original.projectionGenerationId(),
                original.sourceSha256(),
                original.parserRevision(),
                original.target(),
                "b".repeat(64)));
    for (var variant : variants) {
      assertNotEquals(fingerprint, new SynopsisInput(variant, evidence).fingerprint());
    }
  }

  @Test
  void fingerprintBindsFirstLastEvidenceIdentityOrderKindAndCompleteUnicodeContent() {
    var first = text("first", SynopsisEvidence.Kind.TEXT, "第一😀", null);
    var last = text("last", SynopsisEvidence.Kind.TEXT, "尾部", null);
    String original = new SynopsisInput(publication(2), List.of(first, last)).fingerprint();
    for (var items :
        List.of(
            List.of(last, first),
            List.of(text("different", SynopsisEvidence.Kind.TEXT, "第一😀", null), last),
            List.of(first, text("different", SynopsisEvidence.Kind.TEXT, "尾部", null)),
            List.of(text("first", SynopsisEvidence.Kind.TEXT, "改变😀", null), last),
            List.of(first, text("last", SynopsisEvidence.Kind.TEXT, "尾部改变", null)),
            List.of(first, text("last", SynopsisEvidence.Kind.IMAGE_OCR, "尾部", null)))) {
      assertNotEquals(original, new SynopsisInput(publication(2), items).fingerprint());
    }
    assertNotEquals(
        new SynopsisInput(
                publication(1), List.of(text("ab", SynopsisEvidence.Kind.TEXT, "c", null)))
            .fingerprint(),
        new SynopsisInput(
                publication(1), List.of(text("a", SynopsisEvidence.Kind.TEXT, "bc", null)))
            .fingerprint());
  }

  @Test
  void fingerprintBindsMixedPayloadMimeAndBothRealTimeBoundaries() {
    var frame =
        image("frame", SynopsisEvidence.Kind.VIDEO_FRAME, "image/png", new byte[] {1, 2}, TIME);
    var words = text("words", SynopsisEvidence.Kind.VIDEO_TRANSCRIPT, "原音频事实", TIME);
    var ocr = text("ocr", SynopsisEvidence.Kind.VIDEO_OCR, "原画面文字", TIME);
    String original = new SynopsisInput(publication(3), List.of(frame, words, ocr)).fingerprint();
    for (var changed :
        List.of(
            image(
                "frame", SynopsisEvidence.Kind.VIDEO_FRAME, "image/jpeg", new byte[] {1, 2}, TIME),
            image("frame", SynopsisEvidence.Kind.VIDEO_FRAME, "image/png", new byte[] {1, 3}, TIME),
            image(
                "frame",
                SynopsisEvidence.Kind.VIDEO_FRAME,
                "image/png",
                new byte[] {1, 2},
                new SynopsisEvidence.TimeRange(2, 20)),
            image(
                "frame",
                SynopsisEvidence.Kind.VIDEO_FRAME,
                "image/png",
                new byte[] {1, 2},
                new SynopsisEvidence.TimeRange(1, 21)))) {
      assertNotEquals(
          original, new SynopsisInput(publication(3), List.of(changed, words, ocr)).fingerprint());
    }
    assertEquals(
        original, new SynopsisInput(publication(3), List.of(frame, words, ocr)).fingerprint());
  }

  @Test
  void candidateShapeIsImmutableAndPreservesUnicodeAndSourceOrder() {
    var ids = new ArrayList<>(List.of("first", "last"));
    var item = new SynopsisDraft.Item(SynopsisDraft.Section.OVERVIEW, "😀".repeat(1024), ids);
    var items = new ArrayList<>(List.of(item));
    var draft = new SynopsisDraft(false, items);
    ids.clear();
    items.clear();
    assertEquals(List.of("first", "last"), item.evidenceIds());
    assertEquals(1, draft.items().size());
    assertFalse(draft.refused());
    assertThrows(UnsupportedOperationException.class, () -> item.evidenceIds().clear());
    assertThrows(UnsupportedOperationException.class, () -> draft.items().clear());
    assertTrue(new SynopsisDraft(true, List.of()).refused());
    assertTrue(new SynopsisDraft(false, List.of()).items().isEmpty());
  }

  @Test
  void candidateRejectsMalformedTextReferencesAndPartialRefusal() {
    var item = item(SynopsisDraft.Section.OVERVIEW, "source");
    invalid(() -> new SynopsisDraft.Item(null, "text", List.of("source")));
    invalid(
        () ->
            new SynopsisDraft.Item(
                SynopsisDraft.Section.TOPIC, "😀".repeat(1025), List.of("source")));
    invalid(() -> new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, "text", null));
    invalid(() -> new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, "text", List.of()));
    invalid(
        () ->
            new SynopsisDraft.Item(
                SynopsisDraft.Section.TOPIC, "text", List.of("source", "source")));
    invalid(
        () ->
            new SynopsisDraft.Item(
                SynopsisDraft.Section.TOPIC, "text", Arrays.asList((String) null)));
    invalid(
        () ->
            new SynopsisDraft.Item(
                SynopsisDraft.Section.TOPIC,
                "text",
                IntStream.range(0, 9).mapToObj(i -> "s" + i).toList()));
    invalid(() -> new SynopsisDraft(false, null));
    invalid(() -> new SynopsisDraft(false, Arrays.asList((SynopsisDraft.Item) null)));
    invalid(() -> new SynopsisDraft(true, List.of(item)));
    invalid(() -> new SynopsisDraft(false, IntStream.range(0, 33).mapToObj(i -> item).toList()));
  }

  @Test
  void savedEntryContainsOnlyImmutableReferencesAndExactServerTimelineEnvelope() {
    var first =
        new FileSynopsis.Reference(
            "first",
            SHA,
            SynopsisEvidence.Kind.VIDEO_FRAME,
            new SynopsisEvidence.TimeRange(10, 20));
    var last =
        new FileSynopsis.Reference(
            "last",
            SHA,
            SynopsisEvidence.Kind.VIDEO_TRANSCRIPT,
            new SynopsisEvidence.TimeRange(5, 30));
    var references = new ArrayList<>(List.of(first, last));
    var item =
        new SynopsisDraft.Item(SynopsisDraft.Section.TIMELINE, "实际时间内容", List.of("first", "last"));
    var entry = new FileSynopsis.Entry(item, references, new SynopsisEvidence.TimeRange(5, 30));
    references.clear();
    assertEquals(List.of(first, last), entry.evidence());
    assertEquals(new SynopsisEvidence.TimeRange(5, 30), entry.interval());
    assertThrows(UnsupportedOperationException.class, () -> entry.evidence().clear());
    assertEquals(
        List.of("id", "sha256", "kind", "time"),
        Arrays.stream(FileSynopsis.Reference.class.getRecordComponents())
            .map(c -> c.getName())
            .toList());
    invalid(
        () ->
            new FileSynopsis.Entry(
                item, List.of(last, first), new SynopsisEvidence.TimeRange(5, 30)));
    invalid(
        () ->
            new FileSynopsis.Entry(
                item, List.of(first, last), new SynopsisEvidence.TimeRange(6, 30)));
    invalid(
        () ->
            new FileSynopsis.Entry(
                item, List.of(first, last), new SynopsisEvidence.TimeRange(5, 31)));
    invalid(() -> new FileSynopsis.Entry(item, List.of(first, last), null));
  }

  @Test
  void savedReferencesCannotInventTimeOrLoseIdsAndContentHashes() {
    var reference = reference("source");
    invalid(() -> new FileSynopsis.Reference("source", null, SynopsisEvidence.Kind.TEXT, null));
    invalid(
        () -> new FileSynopsis.Reference("source", "invalid", SynopsisEvidence.Kind.TEXT, null));
    invalid(() -> new FileSynopsis.Reference("source", SHA, null, null));
    invalid(() -> new FileSynopsis.Reference("source", SHA, SynopsisEvidence.Kind.TEXT, TIME));
    invalid(
        () -> new FileSynopsis.Reference("source", SHA, SynopsisEvidence.Kind.VIDEO_FRAME, null));
    invalid(() -> new FileSynopsis.Entry(null, List.of(reference), null));
    invalid(
        () -> new FileSynopsis.Entry(item(SynopsisDraft.Section.OVERVIEW, "source"), null, null));
    invalid(
        () ->
            new FileSynopsis.Entry(
                item(SynopsisDraft.Section.OVERVIEW, "source"),
                Arrays.asList((FileSynopsis.Reference) null),
                null));
    invalid(
        () ->
            new FileSynopsis.Entry(
                item(SynopsisDraft.Section.OVERVIEW, "other"), List.of(reference), null));
    invalid(
        () ->
            new FileSynopsis.Entry(
                item(SynopsisDraft.Section.OVERVIEW, "source"), List.of(reference), TIME));
    invalid(
        () ->
            new FileSynopsis.Entry(
                item(SynopsisDraft.Section.TIMELINE, "source"), List.of(reference), TIME));
  }

  @Test
  void completeSynopsisRequiresAllSectionsAndFailuresCannotExposePartialItems() {
    var entries = new ArrayList<>(entries());
    var synopsis = result(entries, null);
    entries.clear();
    assertEquals(3, synopsis.entries().size());
    assertThrows(UnsupportedOperationException.class, () -> synopsis.entries().clear());
    for (String reason :
        List.of(
            "model_failure",
            "model_refused",
            "unsupported_claims",
            "incomplete_evidence",
            "configuration_changed",
            "source_changed",
            "processing_interrupted",
            "processing_timeout")) {
      assertEquals(reason, result(List.of(), reason).unavailableReason());
      invalid(() -> result(entries(), reason));
    }
    invalid(() -> result(List.of(), "arbitrary"));
    invalid(() -> result(List.of(), null));
    var valid = entries();
    for (int i = 0; i < valid.size(); i++) {
      var incomplete = new ArrayList<>(valid);
      incomplete.remove(i);
      invalid(() -> result(incomplete, null));
    }
    var duplicates = new ArrayList<>(valid);
    duplicates.add(valid.getFirst());
    invalid(() -> result(duplicates, null));
    invalid(() -> result(null, null));
    invalid(() -> result(Arrays.asList((FileSynopsis.Entry) null), null));
    invalid(() -> result(IntStream.range(0, 33).mapToObj(i -> valid.getFirst()).toList(), null));
    invalid(() -> new FileSynopsis(null, SHA, "model-v1", "policy-v1", valid, null));
    invalid(() -> new FileSynopsis(publication(1), null, "model-v1", "policy-v1", valid, null));
    invalid(
        () -> new FileSynopsis(publication(1), "invalid", "model-v1", "policy-v1", valid, null));
    invalid(() -> new FileSynopsis(publication(1), SHA, "", "policy-v1", valid, null));
    invalid(() -> new FileSynopsis(publication(1), SHA, "model-v1", "", valid, null));
  }

  @Test
  void allDiagnosticStringsRedactSourceAndGeneratedContent() {
    var text = text("private-id", SynopsisEvidence.Kind.TEXT, "private-content", null);
    var image =
        image("private-image", SynopsisEvidence.Kind.IMAGE, "image/png", new byte[] {1}, null);
    var draft = new SynopsisDraft(false, List.of(item(SynopsisDraft.Section.OVERVIEW, "source")));
    for (Object value :
        List.of(
            text,
            text.content(),
            image.content(),
            TIME,
            new SynopsisInput(publication(1), List.of(text)),
            draft,
            draft.items().getFirst(),
            reference("source"),
            entries().getFirst(),
            result(entries(), null))) {
      assertTrue(value.toString().endsWith("[redacted]"));
      assertFalse(value.toString().contains("private"));
    }
  }

  private static SynopsisEvidence text(
      String id, SynopsisEvidence.Kind kind, String text, SynopsisEvidence.TimeRange time) {
    return new SynopsisEvidence(id, kind, new SynopsisEvidence.Text(text), time);
  }

  private static SynopsisEvidence image(
      String id,
      SynopsisEvidence.Kind kind,
      String mime,
      byte[] bytes,
      SynopsisEvidence.TimeRange time) {
    return new SynopsisEvidence(
        id, kind, new SynopsisEvidence.Image(new VisualImage(mime, bytes)), time);
  }

  private static PublicationVersion publication(int count) {
    return new PublicationVersion(
        "document",
        "publication",
        "source-revision",
        "generation",
        SHA,
        "parser-v1",
        new IndexTarget("embedding", "projection", "embedding-v1", 3),
        SHA,
        count);
  }

  private static PublicationVersion version(
      String document,
      String publication,
      String revision,
      String generation,
      String sha,
      String parser,
      IndexTarget target,
      String manifest) {
    return new PublicationVersion(
        document, publication, revision, generation, sha, parser, target, manifest, 1);
  }

  private static SynopsisDraft.Item item(SynopsisDraft.Section section, String id) {
    return new SynopsisDraft.Item(section, "完整内容", List.of(id));
  }

  private static FileSynopsis.Reference reference(String id) {
    return new FileSynopsis.Reference(id, SHA, SynopsisEvidence.Kind.TEXT, null);
  }

  private static List<FileSynopsis.Entry> entries() {
    return List.of(
            SynopsisDraft.Section.OVERVIEW, SynopsisDraft.Section.TOPIC, SynopsisDraft.Section.TERM)
        .stream()
        .map(s -> new FileSynopsis.Entry(item(s, "source"), List.of(reference("source")), null))
        .toList();
  }

  private static FileSynopsis result(List<FileSynopsis.Entry> entries, String reason) {
    return new FileSynopsis(publication(1), SHA, "model-v1", "policy-v1", entries, reason);
  }

  private static void invalid(Executable operation) {
    assertEquals("invalid_request", assertThrows(ApplicationException.class, operation).code());
  }
}
