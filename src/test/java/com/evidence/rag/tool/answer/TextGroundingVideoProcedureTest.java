package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;

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

class TextGroundingVideoProcedureTest {
  private static final String QUESTION = "青榆X1如何开启夜间模式？";
  private static final String TITLE = "一 青榆 X1 . 桌面净化器";
  private static final String OCR = TITLE + "\n01 / 开机\n短按电源开机\n合成演示 · 虚构产品";
  private static final String SPOKEN = "短按电源开机，长按月亮键3秒开启夜间模式，月亮指示灯变绿表示开启。";
  private static final PublicationVersion PUBLICATION = publication("publication", "revision", "a");
  private final TextGrounding grounding = new TextGrounding();

  @Test
  void plainTextProofCannotBindVideoTitleToSpokenProcedure() {
    var sources = List.of(ocr(PUBLICATION, OCR, 0, 40_000), spoken(SPOKEN));
    assertFalse(
        grounding
            .verifyText(
                QUESTION, sources.stream().map(KnowledgeEvidence::context).toList(), quotes())
            .supported());
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
