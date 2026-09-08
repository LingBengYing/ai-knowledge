package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextGroundingScopeTest {
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @MethodSource("conditionsOutsideChunk")
  void anExactChunkCannotLoseAnAdjacentSamePageApplicabilityCondition(
      String question, String page, String chunk) {
    var source = TextGroundingTest.evidence("condition", page, chunk);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), chunk)));
    assertFalse(result.supported(), page);
    assertEquals("unsafe_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> conditionsOutsideChunk() {
    return Stream.of(
        Arguments.of("项目预算是多少？", "仅适用于试运行，项目预算为470万元", "项目预算为470万元"),
        Arguments.of("项目预算是多少？", "仅适用于试运行\n项目预算为470万元", "项目预算为470万元"),
        Arguments.of("用户是否允许导出报表？", "仅限管理员，用户允许导出报表", "用户允许导出报表"),
        Arguments.of("用户是否允许导出报表？", "仅限管理员。\n用户允许导出报表", "用户允许导出报表"),
        Arguments.of(
            "Are users allowed to export reports?",
            "During the trial, users are allowed to export reports",
            "users are allowed to export reports"),
        Arguments.of(
            "Are users allowed to export reports?",
            "During the trial\nusers are allowed to export reports",
            "users are allowed to export reports"),
        Arguments.of("项目预算是多少？", "项目预算为470万元，仅适用于试运行", "项目预算为470万元"),
        Arguments.of(
            "What is Project A's budget?",
            "Project A's budget is 270 USD, during the trial",
            "Project A's budget is 270 USD"));
  }

  @ParameterizedTest
  @MethodSource("unrelatedTrialMetadata")
  void mentioningATrialInUnrelatedMetadataDoesNotInvalidateAnUnconditionalFact(
      String question, String page, String chunk) {
    var source = TextGroundingTest.evidence("unrelated", page, chunk);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), chunk)));
    assertTrue(result.supported(), page);
    assertEquals(List.of(chunk), result.quotes().stream().map(quote -> quote.quote()).toList());
  }

  static Stream<Arguments> unrelatedTrialMetadata() {
    return Stream.of(
        Arguments.of("项目预算是多少？", "系统记录在试运行期间更新。\n项目预算为470万元", "项目预算为470万元"),
        Arguments.of("用户是否允许导出报表？", "试运行记录编号是R-42。\n用户允许导出报表", "用户允许导出报表"),
        Arguments.of(
            "Are users allowed to export reports?",
            "The trial report was archived.\nUsers are allowed to export reports",
            "Users are allowed to export reports"),
        Arguments.of("项目预算是多少？", "项目预算为470万元。试运行记录另存", "项目预算为470万元"));
  }

  @ParameterizedTest
  @MethodSource("distantSameSubjectConditions")
  void aLaterExplicitConditionOnTheSameSubjectCannotHideInAnotherChunk(
      String question, String fact, String condition) {
    String page = fact + "。\n" + "资料索引。\n".repeat(240) + condition;
    var source = TextGroundingTest.evidence("distant", page, fact);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), fact)));
    assertFalse(result.supported(), condition);
    assertEquals("unsafe_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> distantSameSubjectConditions() {
    return Stream.of(
        Arguments.of("星港计划的预算是多少？", "星港计划的预算为470万元", "星港计划的预算仅适用于试运行"),
        Arguments.of("星港计划的预算是多少？", "星港计划的预算为470万元", "星港计划的预算仅限试运行阶段"),
        Arguments.of("星港计划的预算是多少？", "星港计划的预算为470万元", "星港计划的预算审批通过后才生效"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget is 270 USD",
            "Atlas's budget applies only during the trial"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget is 270 USD",
            "Atlas's budget is subject to approval"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget is 270 USD",
            "Atlas's budget is applicable only to the trial"));
  }

  @ParameterizedTest
  @MethodSource("earlierSameSubjectConditions")
  void aNamedSamePageConditionAlsoBindsAValueInALaterChunk(
      String question, String fact, String condition) {
    String page = condition + "。\n" + "资料索引。\n".repeat(240) + fact;
    var source = TextGroundingTest.evidence("earlier-condition", page, fact);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), fact)));
    assertFalse(result.supported(), condition);
    assertEquals("unsafe_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> earlierSameSubjectConditions() {
    return distantSameSubjectConditions();
  }

  @ParameterizedTest
  @MethodSource("labeledAntecedents")
  void aNeutralLabelCannotTurnAnActualCrossSubjectPremiseIntoAnIndependentStatement(
      String question, String fact, String page) {
    var source = TextGroundingTest.evidence("labeled-condition", page, fact);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), fact)));
    assertFalse(result.supported(), page);
    assertEquals("unsafe_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> labeledAntecedents() {
    return Stream.of(
        Arguments.of("星港计划的预算是多少？", "星港计划的预算为470万元", "条件：只有星云计划的预算获批后，星港计划的预算为470万元"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget is 270 USD",
            "Condition: Only if Atlas Pro's budget is approved, Atlas's budget is 270 USD"));
  }

  @ParameterizedTest
  @MethodSource("differentSubjectConditions")
  void aConditionExplicitlyAboutAnotherSubjectsBudgetDoesNotQualifyThisBudget(
      String question, String fact, String page) {
    var source = TextGroundingTest.evidence("separate", page, fact);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), fact)));
    assertTrue(result.supported(), page);
    assertEquals(List.of(fact), result.quotes().stream().map(quote -> quote.quote()).toList());
  }

  static Stream<Arguments> differentSubjectConditions() {
    String chinese = "星港计划的预算为470万元";
    String english = "Atlas's budget is 270 USD";
    return Stream.of(
        Arguments.of(
            "星港计划的预算是多少？", chinese, "新星港计划的预算仅适用于试运行。\n" + "资料索引。\n".repeat(240) + chinese),
        Arguments.of(
            "What is Atlas's budget?",
            english,
            "Atlas Pro's budget applies only during the trial.\n"
                + "Index entry.\n".repeat(240)
                + english),
        Arguments.of("星港计划的预算是多少？", chinese, "说明：星云计划的预算待审批后执行，" + chinese),
        Arguments.of(
            "What is Atlas's budget?",
            english,
            "Notes: Atlas Pro's budget is subject to approval, " + english),
        Arguments.of("星港计划的预算是多少？", chinese, "星云计划的预算待审批后执行，" + chinese),
        Arguments.of("星港计划的预算是多少？", chinese, chinese + "。星云计划的预算审批通过后才生效"),
        Arguments.of(
            "What is Atlas's budget?",
            english,
            "Atlas Pro's budget is subject to approval, " + english),
        Arguments.of(
            "What is Atlas's budget?",
            english,
            english + ". Atlas Pro's budget is subject to approval"),
        Arguments.of(
            "星港计划的预算是多少？", chinese, chinese + "。\n" + "资料索引。\n".repeat(240) + "新星港计划的预算仅适用于试运行"),
        Arguments.of(
            "What is Atlas's budget?",
            english,
            english
                + ".\n"
                + "Index entry.\n".repeat(240)
                + "Atlas Pro's budget applies only during the trial"));
  }

  @ParameterizedTest
  @MethodSource("crossSubjectAntecedents")
  void anExplicitIfClauseMayConditionTheAnswerEvenWhenItsPremiseNamesAnotherSubject(
      String question, String fact, String page) {
    var source = TextGroundingTest.evidence("antecedent", page, fact);
    assertFalse(
        grounding
            .verify(
                question,
                List.of(source),
                List.of(new GroundingQuote(source.physicalSegmentId(), fact)))
            .supported());
  }

  static Stream<Arguments> crossSubjectAntecedents() {
    return Stream.of(
        Arguments.of("星港计划的预算是多少？", "星港计划的预算为470万元", "只有星云计划的预算获批后，星港计划的预算为470万元"),
        Arguments.of("星港计划的预算是多少？", "星港计划的预算为470万元", "若星云计划的预算审批通过后，星港计划的预算为470万元"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget is 270 USD",
            "If Atlas Pro's budget is approved, Atlas's budget is 270 USD"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget is 270 USD",
            "Only if Atlas Pro's budget is approved, Atlas's budget is 270 USD"));
  }

  @ParameterizedTest
  @MethodSource("differentNames")
  void anOriginalSubstringCannotEraseANamePrefixSuffixOrPredicateSuffix(
      String question, String page, String chunk) {
    var source = TextGroundingTest.evidence("name-boundary", page, chunk);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), chunk)));
    assertFalse(result.supported(), page);
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> differentNames() {
    return Stream.of(
        Arguments.of("星港计划的预算是多少？", "新星港计划的预算为470万元", "星港计划的预算为470万元"),
        Arguments.of("星港计划的预算是多少？", "星港计划二期的预算为470万元", "星港计划二期的预算为470万元"),
        Arguments.of("星港计划的预算是多少？", "星港计划的预算上限为470万元", "星港计划的预算上限为470万元"),
        Arguments.of(
            "What is Atlas's budget?",
            "New Atlas's budget is 270 USD",
            "Atlas's budget is 270 USD"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas Pro's budget is 270 USD",
            "Atlas Pro's budget is 270 USD"),
        Arguments.of(
            "What is Atlas's budget?",
            "Atlas's budget limit is 270 USD",
            "Atlas's budget limit is 270 USD"));
  }

  @ParameterizedTest
  @MethodSource("separatelyNamedFacts")
  void similarlyNamedSubjectsCanEachHaveTheirOwnDifferentVerifiedAnswer(
      String question, String firstFact, String secondFact) {
    var first = TextGroundingTest.evidence("first-name", firstFact, firstFact);
    var second = TextGroundingTest.evidence("second-name", secondFact, secondFact);
    var result =
        grounding.verify(
            question,
            List.of(first, second),
            List.of(
                new GroundingQuote(first.physicalSegmentId(), firstFact),
                new GroundingQuote(second.physicalSegmentId(), secondFact)));
    assertTrue(result.supported());
    assertEquals(
        List.of(firstFact, secondFact),
        result.quotes().stream().map(quote -> quote.quote()).toList());
  }

  static Stream<Arguments> separatelyNamedFacts() {
    return Stream.of(
        Arguments.of("星港计划的预算是多少？新星港计划的预算是多少？", "星港计划的预算为470万元", "新星港计划的预算为650万元"),
        Arguments.of(
            "What is Atlas's budget? What is Atlas Pro's budget?",
            "Atlas's budget is 270 USD",
            "Atlas Pro's budget is 390 USD"));
  }
}
