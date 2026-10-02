package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextFactGroundingTest {
  private final TextGrounding grounding = new TextGrounding();

  @Test
  void provesOnlyPlannedTranscriptFactWithCommonIdentityWithoutChangingWholeQuestionProof() {
    String question = "指示灯的颜色是什么？重启操作是什么？";
    var plan = QuestionPlanning.plan(question).orElseThrow();
    var source = text("重启操作是按住复位键三秒。", "重启操作是按住复位键三秒");
    var quotes = List.of(new GroundingQuote(source.physicalId(), source.snippet()));
    var result = grounding.verifyFact(question, plan.facts().get(1), List.of(source), quotes);
    assertTrue(result.supported());
    assertEquals(List.of(plan.facts().get(1).id()), result.quotes().getFirst().factHashes());
    assertEquals(source.snippet(), result.quotes().getFirst().quote());
    assertFalse(grounding.verifyText(question, List.of(source), quotes).supported());
    var missing = grounding.verifyFact(question, plan.facts().getFirst(), List.of(source), quotes);
    assertFalse(missing.supported());
    assertTrue(missing.quotes().isEmpty());
  }

  @Test
  void retainsFullTranscriptConflictAndConditionChecksOutsideQuotedSpan() {
    String question = "指示灯的颜色是什么？重启操作是什么？";
    var fact = QuestionPlanning.plan(question).orElseThrow().facts().get(1);
    var conflict = text("重启操作是按住复位键三秒。重启操作是断电。", "重启操作是按住复位键三秒");
    var result =
        grounding.verifyFact(
            question,
            fact,
            List.of(conflict),
            List.of(new GroundingQuote(conflict.physicalId(), conflict.snippet())));
    assertFalse(result.supported());
    assertEquals("conflicting_evidence", result.reason());
    var conditional = text("如果设备获批，重启操作是按住复位键三秒。", "重启操作是按住复位键三秒");
    var unsafe =
        grounding.verifyFact(
            question,
            fact,
            List.of(conditional),
            List.of(new GroundingQuote(conditional.physicalId(), conditional.snippet())));
    assertFalse(unsafe.supported());
    assertEquals("unsafe_evidence", unsafe.reason());
  }

  @Test
  void refusesFactsFromAnotherQuestionOrCallerModifiedIdentityRequirementAndOrdinal() {
    String question = "指示灯的颜色是什么？重启操作是什么？";
    var fact = QuestionPlanning.plan(question).orElseThrow().facts().get(1);
    var changedQuestion = QuestionPlanning.plan("外壳的颜色是什么？重启操作是什么？").orElseThrow();
    for (QuestionFact invalid :
        List.of(
            changedQuestion.facts().get(1),
            new QuestionFact(0, fact.id(), fact.requirement()),
            new QuestionFact(1, "a".repeat(64), fact.requirement()),
            new QuestionFact(1, fact.id(), "value\n关闭操作\n"))) {
      var result = grounding.verifyFact(question, invalid, List.of(), List.of());
      assertFalse(result.supported());
      assertEquals("unsupported_question", result.reason());
      assertTrue(result.quotes().isEmpty());
    }
    assertFalse(grounding.verifyFact(question, null, List.of(), List.of()).supported());
  }

  @Test
  void preservesEnglishProcedureProofAndExactAuthoritativeQuoteValidation() {
    String question = "What is the indicator color? How do I restart the device?";
    var fact = QuestionPlanning.plan(question).orElseThrow().facts().get(1);
    var source =
        text(
            "restart the device: press the reset button.",
            "restart the device: press the reset button");
    var result =
        grounding.verifyFact(
            question,
            fact,
            List.of(source),
            List.of(new GroundingQuote(source.physicalId(), source.snippet())));
    assertTrue(result.supported());
    assertEquals(List.of(fact.id()), result.quotes().getFirst().factHashes());
    var invented =
        grounding.verifyFact(
            question,
            fact,
            List.of(source),
            List.of(new GroundingQuote(source.physicalId(), "press a different button")));
    assertFalse(invented.supported());
    assertEquals("invalid_quote", invented.reason());
  }

  private static GroundingText text(String context, String snippet) {
    int index = context.indexOf(snippet);
    int start = context.codePointCount(0, index);
    return new GroundingText(
        "physical-span",
        "video-transcript-context",
        context,
        ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
        start,
        start + snippet.codePointCount(0, snippet.length()));
  }
}
