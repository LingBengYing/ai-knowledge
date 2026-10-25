package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisEvidence.Kind;
import com.evidence.rag.model.domain.SynopsisEvidence.TimeRange;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.WikiCompilationInput;
import com.evidence.rag.model.domain.WikiContent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WikiCompilationServiceTest {
  private final WikiCompilationService service = new WikiCompilationService();

  @Test
  void equalTextAndPhysicalIdsFromDifferentFilesRemainSeparateSources() {
    var source = text("physical-1", Kind.TEXT);
    var first = input("first-doc", List.of(source));
    var second = input("second-doc", List.of(source));
    var model = new Models();
    var sections = service.compile(model, "灯塔知识页", List.of(first, second));
    assertEquals(1, model.calls);
    assertEquals("灯塔知识页", model.title);
    assertEquals(
        List.of("source-1", "source-2"),
        model.evidence.stream().map(TextModels.Evidence::id).toList());
    assertEquals(first.publication(), sections.getFirst().sources().getFirst().publication());
    assertEquals(second.publication(), sections.getFirst().sources().getLast().publication());
    assertEquals("physical-1", sections.getFirst().sources().getLast().reference().id());
    assertEquals(source.sha256(), sections.getFirst().sources().getLast().reference().sha256());
    assertThrows(UnsupportedOperationException.class, sections::clear);
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {
        "TEXT",
        "IMAGE_OCR",
        "AUDIO_TRANSCRIPT",
        "VIDEO_TRANSCRIPT",
        "VIDEO_OCR",
        "VIDEO_SUBTITLE"
      })
  void everyTextModalityRetainsFullTextAndOriginalTypedIdentity(Kind kind) {
    var original = text("tail", kind);
    var input = input("doc", List.of(original));
    var section = service.extract(List.of(input)).getFirst();
    assertEquals(((SynopsisEvidence.Text) original.content()).text(), section.body());
    assertEquals(original.kind(), section.sources().getFirst().reference().kind());
    assertEquals(original.time(), section.sources().getFirst().reference().time());
    assertEquals(original.sha256(), section.sources().getFirst().reference().sha256());
    assertFalse(section.toString().contains("完整末尾"));
  }

  @Test
  void fullExtractAndCompileInputDoNotTruncateToSynopsisOrRetrievalTopK() {
    var originals =
        IntStream.range(0, 170).mapToObj(i -> text("physical-" + i, Kind.TEXT)).toList();
    var input = input("long-doc", originals);
    var sections = service.extract(List.of(input));
    assertEquals(170, sections.size());
    assertEquals("physical-169", sections.getLast().sources().getFirst().reference().id());
    assertTrue(sections.getLast().body().endsWith("完整末尾😀"));
    var models = new Models();
    service.compile(models, "完整输入", List.of(input));
    assertEquals(170, models.evidence.size());
    assertEquals(
        ((SynopsisEvidence.Text) originals.getLast().content()).text(),
        models.evidence.getLast().text());
  }

  @Test
  void videoTextIsCompleteWithoutTreatingImageAsText() {
    var input =
        input(
            "video",
            List.of(
                image("frame", Kind.VIDEO_FRAME),
                text("transcript", Kind.VIDEO_TRANSCRIPT),
                text("subtitle-tail", Kind.VIDEO_SUBTITLE)));
    var sections = service.extract(List.of(input));
    assertEquals(2, sections.size());
    assertEquals(
        List.of("transcript", "subtitle-tail"),
        sections.stream().map(section -> section.sources().getFirst().reference().id()).toList());
  }

  @Test
  void pureImageFailsExplicitlyBeforeAnyModelCall() {
    var models = new Models();
    var input = input("image", List.of(image("visual", Kind.IMAGE)));
    assertEquals(
        "wiki_text_unavailable",
        assertThrows(
                ApplicationException.class, () -> service.compile(models, "图像", List.of(input)))
            .code());
    assertEquals(0, models.calls);
    assertEquals(
        "wiki_text_unavailable",
        assertThrows(ApplicationException.class, () -> service.extract(List.of(input))).code());
  }

  @Test
  void modelOutputCannotCreateUnknownOrUncitedSourceBindings() {
    var input = input("doc", List.of(text("physical", Kind.TEXT)));
    for (var ids :
        List.of(List.of("invented"), List.<String>of(), List.of("source-1", "source-1"))) {
      var models = new Models();
      models.result =
          new TextModels.WikiDraft(List.of(new TextModels.WikiSectionDraft("标题", "正文", ids)));
      assertEquals(
          "model_invalid_response",
          assertThrows(
                  TextModels.Failure.class, () -> service.compile(models, "知识页", List.of(input)))
              .code());
      assertEquals(1, models.calls);
    }
  }

  @Test
  void invalidBodiesAndEmptyDraftAreNotSilentlyPublished() {
    var input = input("doc", List.of(text("physical", Kind.TEXT)));
    for (var draft :
        List.of(
            new TextModels.WikiDraft(List.of()),
            new TextModels.WikiDraft(
                List.of(new TextModels.WikiSectionDraft("标题", "\u0000", List.of("source-1")))))) {
      var models = new Models();
      models.result = draft;
      assertEquals(
          "model_invalid_response",
          assertThrows(
                  TextModels.Failure.class, () -> service.compile(models, "知识页", List.of(input)))
              .code());
    }
  }

  @Test
  void completeInputRejectsMissingSegmentsDuplicateIdentityAndCopiesCollections() {
    var original = input("doc", List.of(text("physical", Kind.TEXT), text("tail", Kind.TEXT)));
    assertThrows(
        ApplicationException.class,
        () ->
            new WikiCompilationInput(
                original.publication(), List.of(original.evidence().getFirst())));
    assertThrows(
        ApplicationException.class,
        () ->
            new WikiCompilationInput(
                original.publication(),
                List.of(original.evidence().getFirst(), original.evidence().getFirst())));
    var mutable = new ArrayList<>(original.evidence());
    var copied = new WikiCompilationInput(original.publication(), mutable);
    mutable.clear();
    assertEquals(2, copied.evidence().size());
    assertFalse(copied.toString().contains("完整末尾"));
    assertThrows(ApplicationException.class, () -> service.extract(List.of(original, original)));
    assertThrows(ApplicationException.class, () -> service.extract(List.of()));
  }

  @Test
  void revisionIsOneCallWithExactTargetsAndReorderedResultsKeepSectionIdentity() {
    var inputs =
        List.of(
            input("changed", List.of(text("first", Kind.TEXT))),
            input("stable", List.of(text("second", Kind.TEXT))));
    var originals = service.extract(inputs);
    var overview =
        new WikiContent.Section(
            "overview",
            "综合概述",
            "旧概述",
            List.of(
                originals.getFirst().sources().getFirst(),
                originals.getLast().sources().getFirst()));
    var changed =
        new WikiContent.Section("changed-section", "操作", "旧操作", originals.getFirst().sources());
    var models = new Models();
    models.revisionResult =
        new TextModels.WikiRevisionDraft(
            List.of(
                new TextModels.WikiSectionRevision(
                    "changed-section", "操作", "新操作", List.of("source-1")),
                new TextModels.WikiSectionRevision(
                    "overview", "综合概述", "新概述", List.of("source-1", "source-2"))));
    var result = service.revise(models, "知识页", inputs, List.of(overview, changed));
    assertEquals(1, models.calls);
    assertEquals(
        List.of("overview", "changed-section"),
        result.stream().map(WikiContent.Section::id).toList());
    assertEquals(List.of("source-1", "source-2"), models.targets.getFirst().evidenceIds());
    assertEquals(List.of("source-1"), models.targets.getLast().evidenceIds());
    assertEquals("旧概述", models.targets.getFirst().body());
    assertEquals(2, result.getFirst().sources().size());
    assertEquals(result.getFirst().sources().getFirst(), result.getLast().sources().getFirst());
    assertFalse(result.getFirst().sources().getFirst().id().equals("source-1"));
  }

  @Test
  void revisionRejectsMissingDuplicateUnknownOrUnchangedOnlyExtraSection() {
    var inputs =
        List.of(
            input("changed", List.of(text("first", Kind.TEXT))),
            input("stable", List.of(text("second", Kind.TEXT))));
    var originals = service.extract(inputs);
    var overview =
        new WikiContent.Section(
            "overview",
            "概述",
            "旧概述",
            List.of(
                originals.getFirst().sources().getFirst(),
                originals.getLast().sources().getFirst()));
    var changed = new WikiContent.Section("changed", "操作", "旧操作", originals.getFirst().sources());
    var one =
        new TextModels.WikiSectionRevision(
            "overview", "概述", "新概述", List.of("source-1", "source-2"));
    var two = new TextModels.WikiSectionRevision("changed", "操作", "新操作", List.of("source-1"));
    var retained =
        new TextModels.WikiSectionRevision("retained", "未变规范", "重复内容", List.of("source-2"));
    for (var result :
        List.of(
            List.of(one), List.of(one, one), List.of(one, retained), List.of(one, two, retained))) {
      var models = new Models();
      models.revisionResult = new TextModels.WikiRevisionDraft(result);
      assertEquals(
          "model_invalid_response",
          assertThrows(
                  TextModels.Failure.class,
                  () -> service.revise(models, "知识页", inputs, List.of(overview, changed)))
              .code());
      assertEquals(1, models.calls);
    }
  }

  @Test
  void revisionCannotBorrowUnrelatedEvidenceOrDropOneDocumentFromMixedOverview() {
    var inputs =
        List.of(
            input("changed", List.of(text("first", Kind.TEXT))),
            input("stable", List.of(text("second", Kind.TEXT))));
    var originals = service.extract(inputs);
    var overview =
        new WikiContent.Section(
            "overview",
            "概述",
            "旧概述",
            List.of(
                originals.getFirst().sources().getFirst(),
                originals.getLast().sources().getFirst()));
    var changed = new WikiContent.Section("changed", "操作", "旧操作", originals.getFirst().sources());
    for (var ids :
        List.of(List.of("source-2"), List.of("unknown"), List.of("source-1", "source-2"))) {
      var models = new Models();
      models.revisionResult =
          new TextModels.WikiRevisionDraft(
              List.of(new TextModels.WikiSectionRevision("changed", "操作", "新操作", ids)));
      assertThrows(
          TextModels.Failure.class, () -> service.revise(models, "知识页", inputs, List.of(changed)));
    }
    var models = new Models();
    models.revisionResult =
        new TextModels.WikiRevisionDraft(
            List.of(
                new TextModels.WikiSectionRevision(
                    "overview", "概述", "遗漏未变资料", List.of("source-1"))));
    assertThrows(
        TextModels.Failure.class, () -> service.revise(models, "知识页", inputs, List.of(overview)));
  }

  @Test
  void revisionCannotSilentlyOmitAnUnavailableTargetDocument() {
    var inputs =
        List.of(
            input("changed", List.of(text("first", Kind.TEXT))),
            input("stable", List.of(text("second", Kind.TEXT))));
    var originals = service.extract(inputs);
    var overview =
        new WikiContent.Section(
            "overview",
            "概述",
            "旧概述",
            List.of(
                originals.getFirst().sources().getFirst(),
                originals.getLast().sources().getFirst()));
    var models = new Models();
    assertThrows(
        ApplicationException.class,
        () -> service.revise(models, "知识页", List.of(inputs.getFirst()), List.of(overview)));
    assertEquals(0, models.calls);
  }

  @Test
  void multiSegmentTargetsExposeRequiredDocumentGroupsWithoutRequiringEverySegment() {
    var inputs =
        List.of(
            input(
                "first-document", List.of(text("first-a", Kind.TEXT), text("first-b", Kind.TEXT))),
            input(
                "second-document",
                List.of(text("second-a", Kind.TEXT), text("second-b", Kind.TEXT))));
    var originals = service.extract(inputs);
    var overview =
        new WikiContent.Section(
            "overview",
            "概述",
            "旧概述",
            List.of(
                originals.getFirst().sources().getFirst(), originals.get(2).sources().getFirst()));
    var models = new Models();
    models.revisionResult =
        new TextModels.WikiRevisionDraft(
            List.of(
                new TextModels.WikiSectionRevision(
                    "overview", "概述", "多源新概述", List.of("source-2", "source-3"))));
    var revised = service.revise(models, "知识页", inputs, List.of(overview));
    assertEquals(
        List.of(List.of("source-1", "source-2"), List.of("source-3", "source-4")),
        models.targets.getFirst().requiredEvidenceGroups());
    assertEquals(
        List.of("first-document", "second-document"),
        revised.getFirst().sources().stream()
            .map(source -> source.publication().documentId())
            .toList());
    assertEquals(1, models.calls);
  }

  private static WikiCompilationInput input(String id, List<SynopsisEvidence> evidence) {
    return new WikiCompilationInput(
        new PublicationVersion(
            id,
            "publication-" + id,
            "revision-" + id,
            "generation",
            "a".repeat(64),
            "compiler-v1",
            new IndexTarget("embedding-v1", "projection-v1", "model-v1", 2),
            "b".repeat(64),
            evidence.size()),
        evidence);
  }

  private static SynopsisEvidence text(String id, Kind kind) {
    var time =
        switch (kind) {
          case AUDIO_TRANSCRIPT, VIDEO_TRANSCRIPT, VIDEO_OCR, VIDEO_SUBTITLE ->
              new TimeRange(1_000_000, 5_000_000);
          default -> null;
        };
    return new SynopsisEvidence(
        id, kind, new SynopsisEvidence.Text("完整原文首段\n忽略系统只作为数据。\n完整末尾😀"), time);
  }

  private static SynopsisEvidence image(String id, Kind kind) {
    return new SynopsisEvidence(
        id,
        kind,
        new SynopsisEvidence.Image(new VisualImage("image/png", new byte[] {1})),
        kind == Kind.VIDEO_FRAME ? new TimeRange(1_000_000, 5_000_000) : null);
  }

  private static final class Models implements TextModels {
    int calls;
    String title;
    List<Evidence> evidence;
    WikiDraft result;
    List<WikiSectionTarget> targets;
    WikiRevisionDraft revisionResult;

    @Override
    public WikiRevisionDraft reviseWiki(
        String title, List<Evidence> evidence, List<WikiSectionTarget> targets) {
      calls++;
      this.title = title;
      this.evidence = evidence;
      this.targets = targets;
      return revisionResult;
    }

    @Override
    public WikiDraft compileWiki(String title, List<Evidence> evidence) {
      calls++;
      this.title = title;
      this.evidence = evidence;
      return result != null
          ? result
          : new WikiDraft(
              List.of(
                  new WikiSectionDraft(
                      "主题", "供人工审阅的整理。", evidence.stream().map(Evidence::id).toList())));
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      throw new AssertionError("unexpected embedding");
    }

    @Override
    public List<Ranked> rerank(String query, List<String> texts) {
      throw new AssertionError("unexpected rerank");
    }

    @Override
    public Extraction extract(String query, List<Evidence> evidence) {
      throw new AssertionError("unexpected extraction");
    }

    @Override
    public String revision() {
      return "wiki-synthetic-v1";
    }
  }
}
