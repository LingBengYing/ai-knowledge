package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionPlanningTest {
  @Test
  void preservesCompleteChineseQuestionAndEveryTrailingRequirement() {
    String question = "指示灯的颜色是什么？重启操作是什么？";
    var plan = QuestionPlanning.plan(question).orElseThrow();
    assertEquals(question, plan.question());
    assertEquals(sha(question), plan.questionSha256());
    assertEquals(QuestionPlanning.VERSION, plan.plannerRevision());
    assertEquals(
        List.of("value\n指示灯的颜色\n", "value\n重启操作\n"),
        plan.facts().stream().map(fact -> fact.requirement()).toList());
    for (int ordinal = 0; ordinal < plan.facts().size(); ordinal++) {
      var fact = plan.facts().get(ordinal);
      assertEquals(ordinal, fact.ordinal());
      assertEquals(
          sha("video-fact-v1\n" + sha(question) + "\n" + ordinal + "\n" + sha(fact.requirement())),
          fact.id());
    }
    assertEquals(plan, QuestionPlanning.plan(question).orElseThrow());
    assertFalse(plan.toString().contains("指示灯"));
    assertFalse(plan.facts().getFirst().toString().contains("指示灯"));
  }

  @Test
  void preservesEnglishProcedureAndChineseSharedSubjectWithoutRewritingQuestions() {
    var english =
        QuestionPlanning.plan("What is the indicator color? How do I restart the device?")
            .orElseThrow();
    assertEquals(
        List.of("value\nindicator color\n", "procedure\nrestart the device"),
        english.facts().stream().map(fact -> fact.requirement()).toList());
    var chinese = QuestionPlanning.plan("设备的颜色和复位操作分别是什么？").orElseThrow();
    assertEquals(
        List.of("value\n设备的颜色\n", "value\n设备的复位操作\n"),
        chinese.facts().stream().map(fact -> fact.requirement()).toList());
    var colors = QuestionPlanning.plan("A区和B区分别是什么颜色？").orElseThrow();
    assertEquals(
        List.of("color\nA区", "color\nB区"),
        colors.facts().stream().map(fact -> fact.requirement()).toList());
  }

  @Test
  void identityBindsWholeQuestionIncludingOtherFactsAndOrdering() {
    var first = QuestionPlanning.plan("指示灯的颜色是什么？重启操作是什么？").orElseThrow();
    var changedTail = QuestionPlanning.plan("指示灯的颜色是什么？关闭操作是什么？").orElseThrow();
    assertFalse(first.facts().getFirst().id().equals(changedTail.facts().getFirst().id()));
    var reversed = QuestionPlanning.plan("重启操作是什么？指示灯的颜色是什么？").orElseThrow();
    assertFalse(first.facts().getFirst().id().equals(reversed.facts().getLast().id()));
  }

  @Test
  void rejectsUnsupportedSharedConditionsInsteadOfDroppingThem() {
    for (String question :
        List.of(
            "试运行期间，A区和B区分别是什么颜色？",
            "开机时A区和B区分别是什么颜色？",
            "如果设备获批，A区和B区分别是什么颜色？",
            "设备的外壳和指示灯分别是什么颜色？",
            "When in test mode, what is the indicator color? What is the restart operation?")) {
      assertTrue(QuestionPlanning.plan(question).isEmpty(), question);
    }
  }

  @Test
  void refusesOverflowInvalidUnicodeAndUnsupportedTailWithoutTruncation() {
    assertTrue(QuestionPlanning.plan(null).isEmpty());
    assertTrue(QuestionPlanning.plan("  ").isEmpty());
    assertTrue(QuestionPlanning.plan("状态是什么？\u0000").isEmpty());
    assertTrue(QuestionPlanning.plan("状态是什么？\ud800").isEmpty());
    assertTrue(QuestionPlanning.plan("界".repeat(1366)).isEmpty());
    assertTrue(QuestionPlanning.plan("指示灯的颜色是什么？为什么需要复位？").isEmpty());
    var question = new StringBuilder();
    for (int index = 0; index < 9; index++) {
      question.append("项目").append(index).append("的颜色是什么？");
    }
    assertTrue(QuestionPlanning.plan(question.toString()).isEmpty());
  }

  @Test
  void comparesCanonicalValuesOnlyAfterIndependentModalityProofs() {
    String question = "A区和B区分别是什么颜色？";
    var fact = QuestionPlanning.plan(question).orElseThrow().facts().getFirst();
    assertTrue(QuestionPlanning.agree(question, fact, List.of("A区为红色。"), List.of("A区是红色")));
    assertFalse(QuestionPlanning.agree(question, fact, List.of("A区为红色"), List.of("A区是蓝色")));
    assertFalse(QuestionPlanning.agree(question, fact, List.of("红色区域"), List.of("A区是红色")));
    assertFalse(
        QuestionPlanning.agree(question, fact, List.of("A区是红色", "A区是蓝色"), List.of("A区是红色")));
    assertFalse(QuestionPlanning.agree(question, fact, List.of(), List.of("A区是红色")));
    assertFalse(QuestionPlanning.agree(question, null, List.of("A区是红色"), List.of("A区是红色")));
  }

  @Test
  void comparesBooleanPolarityAndDoesNotAcceptFactFromAnotherCompleteQuestion() {
    String question = "Does the device support export? What is the panel color?";
    var fact = QuestionPlanning.plan(question).orElseThrow().facts().getFirst();
    assertTrue(
        QuestionPlanning.agree(
            question,
            fact,
            List.of("The device supports export"),
            List.of("device supports export")));
    assertFalse(
        QuestionPlanning.agree(
            question,
            fact,
            List.of("The device supports export"),
            List.of("device does not support export")));
    assertFalse(
        QuestionPlanning.agree(
            "Does the device support export? What is the indicator color?",
            fact,
            List.of("The device supports export"),
            List.of("device supports export")));
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
