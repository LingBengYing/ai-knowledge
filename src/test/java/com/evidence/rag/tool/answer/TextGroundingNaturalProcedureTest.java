package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextGroundingNaturalProcedureTest {
  private static final String QUESTION = "青榆X1如何开启夜间模式？";
  private static final String ACTION = "长按月亮键3秒，开启夜间模式。";
  private static final String CONFIRMATION = "月亮指示灯变绿，表示开启。";
  private static final String PAGE =
      """
      青榆 X1  /  桌面净化器
      夜间模式使用说明
      合成资料 · 虚构产品 · 仅用于本次产品帮助功能联调
      青榆 X1 合成使用说明书 第 1 页 / 共 2 页
      适用产品：青榆X1桌面净化器。
      本页介绍如何开机、开启夜间模式，以及如何确认开启。
      01 开机
      短按电源开机。
      02 开启夜间模式
      长按月亮键3秒，开启夜间模式。
      03 确认开启
      月亮指示灯变绿，表示开启。
      提示：此说明对应配套的“夜间模式操作教程”视频。
      """;
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @ValueSource(strings = {"青榆X1如何开启夜间模式？", "青榆 X1怎么开启夜间模式？"})
  void realManualFormatRetainsProductPrerequisiteAndConfirmationInOneOriginalCitation(
      String question) {
    var source = source("manual", PAGE, PAGE);
    var result =
        grounding.verifyText(
            question, List.of(source), List.of(new GroundingQuote("manual", ACTION)));
    assertTrue(result.supported(), result.reason());
    assertEquals(1, result.quotes().size());
    var quote = result.quotes().getFirst();
    int start = PAGE.indexOf("适用产品：");
    int end = PAGE.indexOf(CONFIRMATION) + CONFIRMATION.length();
    assertEquals(PAGE.substring(start, end), quote.quote());
    assertEquals(PAGE.codePointCount(0, start), quote.start());
    assertEquals(PAGE.codePointCount(0, end), quote.end());
    assertEquals("manual", quote.physicalId());
    assertEquals(1, quote.factHashes().size());
    assertFalse(quote.quote().contains("提示："));
  }

  @Test
  void exactActionWithoutFinalPunctuationStillHasCompleteServerOwnedCitation() {
    var source = source("manual", PAGE, PAGE);
    var result =
        grounding.verifyText(
            QUESTION,
            List.of(source),
            List.of(new GroundingQuote("manual", ACTION.substring(0, ACTION.length() - 1))));
    assertTrue(result.supported(), result.reason());
    assertTrue(result.quotes().getFirst().quote().endsWith(CONFIRMATION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"青榆X10", "青榆X2", "蓝桐X1"})
  void anotherProductCannotProveTheRequestedModel(String product) {
    String page = PAGE.replace("适用产品：青榆X1桌面净化器。", "适用产品：" + product + "桌面净化器。");
    var result =
        grounding.verifyText(
            QUESTION,
            List.of(source("manual", page, page)),
            List.of(new GroundingQuote("manual", ACTION)));
    assertFalse(result.supported());
    assertEquals("incomplete_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  @Test
  void videoCannotBorrowProductIdentityFromAnotherOriginalSource() {
    String video = "短按电源开机，长按月亮键3秒开启夜间模式，月亮指示灯变绿表示开启。";
    var result =
        grounding.verifyText(
            QUESTION,
            List.of(source("manual", PAGE, PAGE), source("video", video, video)),
            List.of(new GroundingQuote("video", video)));
    assertFalse(result.supported());
    assertEquals("incomplete_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  @Test
  void partialActionCannotDropTheRequiredHoldDuration() {
    var result =
        grounding.verifyText(
            QUESTION,
            List.of(source("manual", PAGE, PAGE)),
            List.of(new GroundingQuote("manual", "开启夜间模式。")));
    assertFalse(result.supported());
    assertEquals("incomplete_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  @Test
  void candidateCannotOmitTheProductOrTheConfirmation() {
    for (String snippet : List.of(ACTION, PAGE.substring(0, PAGE.indexOf("03 确认开启")))) {
      var result =
          grounding.verifyText(
              QUESTION,
              List.of(source("manual", PAGE, snippet)),
              List.of(new GroundingQuote("manual", ACTION)));
      assertFalse(result.supported());
      assertEquals("incomplete_evidence", result.reason());
      assertTrue(result.quotes().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "长按月亮键3秒，不能开启夜间模式。",
        "长按月亮键3秒，不会开启夜间模式。",
        "长按月亮键3秒，只有设备获批后才可开启夜间模式。",
        "长按月亮键3秒，开启夜间模式（错误）。"
      })
  void negativeOrConditionalActionDoesNotBecomePositiveSupport(String action) {
    String page = PAGE.replace(ACTION, action);
    var result =
        grounding.verifyText(
            QUESTION,
            List.of(source("manual", page, page)),
            List.of(new GroundingQuote("manual", action)));
    assertFalse(result.supported(), action);
    assertTrue(result.quotes().isEmpty());
  }

  @Test
  void conflictingUnquotedProcedureCannotBeHiddenByTheModelQuote() {
    String other = PAGE.replace("长按月亮键3秒", "长按月亮键5秒");
    var result =
        grounding.verifyText(
            QUESTION,
            List.of(source("manual", PAGE, PAGE), source("other", other, other)),
            List.of(new GroundingQuote("manual", ACTION)));
    assertFalse(result.supported());
    assertEquals("conflicting_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  private static GroundingText source(String id, String context, String snippet) {
    int start = context.indexOf(snippet);
    return new GroundingText(
        id,
        "source/" + id,
        context,
        ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
        context.codePointCount(0, start),
        context.codePointCount(0, start + snippet.length()));
  }
}
