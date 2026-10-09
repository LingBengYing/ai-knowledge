package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import com.evidence.rag.model.domain.PublicationVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextGroundingVideoProcedureTest {
  private static final String QUESTION = "青榆X1如何开启夜间模式？";
  private static final String TITLE = "一 青榆 X1 . 桌面净化器";
  private static final String OCR = TITLE + "\n01 / 开机\n短按电源开机\n合成演示 · 虚构产品";
  private static final String SPOKEN = "短按电源开机，长按月亮键3秒开启夜间模式，月亮指示灯变绿表示开启。";
  private static final PublicationVersion PUBLICATION = publication("publication", "revision", "a");
  private final TextGrounding grounding = new TextGrounding();

  @Test
  void sameVideoTitleAndCompleteSpokenProcedureKeepIndependentOriginalLocators() {
    var sources = List.of(ocr(PUBLICATION, OCR, 0, 40_000), spoken(SPOKEN));
    var result = grounding.verifyKnowledge(QUESTION, sources, quotes());
    assertTrue(result.proof().supported(), result.proof().reason());
    assertEquals(
        List.of(TITLE, SPOKEN), result.proof().quotes().stream().map(q -> q.quote()).toList());
    assertEquals(
        List.of("ocr", "spoken"),
        result.proof().quotes().stream().map(q -> q.physicalId()).toList());
    assertEquals(1, result.dependencies().size());
    assertEquals(result.proof().quotes().getLast(), result.dependencies().getFirst().dependent());
    assertEquals(result.proof().quotes().getFirst(), result.dependencies().getFirst().required());
    assertEquals(0, result.proof().quotes().getFirst().start());
    assertEquals(TITLE.codePointCount(0, TITLE.length()), result.proof().quotes().getFirst().end());
    assertFalse(
        grounding
            .verifyText(
                QUESTION, sources.stream().map(KnowledgeEvidence::context).toList(), quotes())
            .supported());
  }

  @ParameterizedTest
  @ValueSource(strings = {"publication", "revision", "sha"})
  void cannotBorrowTitleFromAnotherPublicationRevisionOrOriginal(String changed) {
    var publication =
        publication(
            changed.equals("publication") ? "other" : "publication",
            changed.equals("revision") ? "other" : "revision",
            changed.equals("sha") ? "b" : "a");
    var result =
        grounding.verifyKnowledge(
            QUESTION, List.of(ocr(publication, OCR, 0, 40_000), spoken(SPOKEN)), quotes());
    assertFalse(result.proof().supported());
    assertTrue(result.dependencies().isEmpty());
  }

  @Test
  void adjacentButNonIntersectingFrameCannotSupplyIdentity() {
    var result =
        grounding.verifyKnowledge(
            QUESTION,
            List.of(ocr(PUBLICATION, OCR, 12_410_000, 12_450_000), spoken(SPOKEN)),
            quotes());
    assertFalse(result.proof().supported());
    assertTrue(result.dependencies().isEmpty());
  }

  @Test
  void independentManualAndVideoProofRemainAvailableTogether() {
    String manual =
        "适用产品：青榆X1桌面净化器。\n01 开机\n短按电源开机。\n02 开启夜间模式\n" + "长按月亮键3秒，开启夜间模式。\n03 确认开启\n月亮指示灯变绿，表示开启。";
    var document =
        new KnowledgeEvidence(
            new ProductHelpEvidence(
                publication("manual-publication", "manual-revision", "d"),
                "manual",
                "synthetic.pdf",
                "application/pdf",
                ProductHelpEvidence.Kind.DOCUMENT_TEXT,
                manual,
                1,
                0,
                manual.length(),
                null,
                null,
                "source_text"),
            new GroundingText(
                "manual",
                "manual/page/1",
                manual,
                ModelValues.sha256(manual.getBytes(StandardCharsets.UTF_8)),
                0,
                manual.length()));
    var result =
        grounding.verifyKnowledge(
            QUESTION,
            List.of(document, ocr(PUBLICATION, OCR, 0, 40_000), spoken(SPOKEN)),
            List.of(
                new GroundingQuote("manual", "长按月亮键3秒，开启夜间模式。"),
                new GroundingQuote("ocr", TITLE),
                new GroundingQuote("spoken", SPOKEN)));
    assertTrue(result.proof().supported(), result.proof().reason());
    assertEquals(
        List.of("manual", "ocr", "spoken"),
        result.proof().quotes().stream().map(q -> q.physicalId()).toList());
    assertEquals(1, result.dependencies().size());
  }

  @Test
  void aCandidateCannotClipTheUnquotedRemainderOfItsTranscript() {
    var original = spoken(SPOKEN);
    String chunk = "短按电源开机，长按月亮键3秒开启夜间模式";
    var clipped =
        new KnowledgeEvidence(
            new ProductHelpEvidence(
                PUBLICATION,
                "spoken",
                "synthetic.mp4",
                "video/mp4",
                ProductHelpEvidence.Kind.VIDEO_TRANSCRIPT,
                chunk,
                null,
                null,
                null,
                0L,
                7_800_000L,
                "machine_asr"),
            new GroundingText(
                "spoken",
                original.context().contextId(),
                SPOKEN,
                original.context().contextSha256(),
                0,
                chunk.length()));
    var result =
        grounding.verifyKnowledge(
            QUESTION,
            List.of(ocr(PUBLICATION, OCR, 0, 40_000), clipped),
            List.of(new GroundingQuote("ocr", TITLE), new GroundingQuote("spoken", chunk)));
    assertFalse(result.proof().supported());
    assertTrue(result.dependencies().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"青榆 X10 · 桌面净化器", "青榆 X2 · 桌面净化器", "蓝桐 X1 · 桌面净化器"})
  void completeProductModelMustMatch(String title) {
    var result =
        grounding.verifyKnowledge(
            QUESTION,
            List.of(ocr(PUBLICATION, OCR.replace(TITLE, title), 0, 40_000), spoken(SPOKEN)),
            List.of(new GroundingQuote("ocr", title), new GroundingQuote("spoken", SPOKEN)));
    assertFalse(result.proof().supported());
  }

  @Test
  void bothOriginalSourcesMustActuallyBeQuoted() {
    var sources = List.of(ocr(PUBLICATION, OCR, 0, 40_000), spoken(SPOKEN));
    for (var quote : quotes()) {
      var result = grounding.verifyKnowledge(QUESTION, sources, List.of(quote));
      assertFalse(result.proof().supported());
      assertTrue(result.dependencies().isEmpty());
    }
    var partial =
        grounding.verifyKnowledge(
            QUESTION,
            sources,
            List.of(
                new GroundingQuote("ocr", TITLE), new GroundingQuote("spoken", "长按月亮键3秒开启夜间模式")));
    assertFalse(partial.proof().supported());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "短按电源开机，长按月亮键3秒不能开启夜间模式，月亮指示灯变绿表示开启。",
        "短按电源开机，长按月亮键3秒，只有设备获批后才可开启夜间模式。",
        "短按电源开机，长按月亮键3秒开启夜间模式（错误）。"
      })
  void completeTranscriptRetainsNegationsAndConditions(String transcript) {
    var result =
        grounding.verifyKnowledge(
            QUESTION,
            List.of(ocr(PUBLICATION, OCR, 0, 40_000), spoken(transcript)),
            List.of(new GroundingQuote("ocr", TITLE), new GroundingQuote("spoken", transcript)));
    assertFalse(result.proof().supported());
    assertTrue(result.dependencies().isEmpty());
  }

  private static List<GroundingQuote> quotes() {
    return List.of(new GroundingQuote("ocr", TITLE), new GroundingQuote("spoken", SPOKEN));
  }

  private static KnowledgeEvidence ocr(
      PublicationVersion publication, String text, long start, long end) {
    return evidence(
        "ocr",
        publication,
        ProductHelpEvidence.Kind.VIDEO_FRAME_OCR,
        text,
        start,
        end,
        "machine_ocr");
  }

  private static KnowledgeEvidence spoken(String text) {
    return evidence(
        "spoken",
        PUBLICATION,
        ProductHelpEvidence.Kind.VIDEO_TRANSCRIPT,
        text,
        0,
        12_410_000,
        "machine_asr");
  }

  private static KnowledgeEvidence evidence(
      String id,
      PublicationVersion publication,
      ProductHelpEvidence.Kind kind,
      String text,
      long start,
      long end,
      String origin) {
    return new KnowledgeEvidence(
        new ProductHelpEvidence(
            publication,
            id,
            "synthetic.mp4",
            "video/mp4",
            kind,
            text,
            null,
            null,
            null,
            start,
            end,
            origin),
        new GroundingText(
            id,
            publication.publicationId() + ":" + id,
            text,
            ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)),
            0,
            text.codePointCount(0, text.length())));
  }

  private static PublicationVersion publication(String id, String revision, String sha) {
    return new PublicationVersion(
        "document",
        id,
        revision,
        "generation",
        sha.repeat(64),
        "java-video-compiler-v4:" + "b".repeat(64),
        new IndexTarget("embedding", "projection", "model-v1", 2),
        "c".repeat(64),
        2);
  }
}
