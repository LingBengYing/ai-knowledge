package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextGroundingConflictTest {
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @MethodSource("contradictions")
  void detectsSameFactConflictInEveryCandidateEvenWhenTheModelOnlyQuotesOne(
      String question, String assertion, String contradiction) {
    var first = TextGroundingTest.evidence("first", assertion, assertion);
    var second = TextGroundingTest.evidence("second", contradiction, contradiction);
    var result =
        grounding.verify(
            question,
            List.of(first, second),
            List.of(new GroundingQuote(first.physicalSegmentId(), assertion)));
    assertFalse(result.supported());
    assertEquals("conflicting_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> contradictions() {
    return Stream.of(
        Arguments.of("项目预算是多少？", "项目预算为1200万元", "项目预算为1300万元"),
        Arguments.of("项目状态是什么？", "项目状态是正常", "项目状态是暂停"),
        Arguments.of("项目名称是什么？", "项目名称是星河", "项目名称是海山"),
        Arguments.of("项目编号是什么？", "项目编号是P-10", "项目编号是P-11"),
        Arguments.of("项目日期是什么？", "项目日期是2026年12月18日", "项目日期是2026年12月20日"),
        Arguments.of("项目折扣是多少？", "项目折扣为20%", "项目折扣为30%"),
        Arguments.of("项目工期是多少？", "项目工期为3天", "项目工期为4天"),
        Arguments.of("系统容量是多少？", "系统容量为1TB", "系统容量为2TB"),
        Arguments.of("项目位置是什么？", "项目位置是东区", "项目位置是西区"),
        Arguments.of("用户是否允许导出报告？", "用户允许导出报告", "用户不允许导出报告"),
        Arguments.of("星港系统是否支持离线导出？", "星港系统支持离线导出", "星港系统不支持离线导出"),
        Arguments.of(
            "What is Project A's budget?",
            "Project A's budget is 1200 USD",
            "Project A's budget is 1300 USD"));
  }

  @Test
  void numericPresentationAndNegativePermissionSynonymsAreNotConflicts() {
    for (var item :
        List.of(
            List.of("项目预算是多少？", "项目预算为1,200万元", "项目预算为1200万元"),
            List.of("用户是否允许导出报告？", "用户不允许导出报告", "用户未被允许导出报告"))) {
      var first = TextGroundingTest.evidence("first", item.get(1), item.get(1));
      var second = TextGroundingTest.evidence("second", item.get(2), item.get(2));
      assertTrue(
          grounding
              .verify(
                  item.get(0),
                  List.of(first, second),
                  List.of(new GroundingQuote(first.physicalSegmentId(), item.get(1))))
              .supported());
    }
  }

  @Test
  void differentSubjectsAreNotMistakenForTheSameRequestedFact() {
    String firstFact = "海山计划的预算为1200万元";
    String secondFact = "星港计划的预算为1300万元";
    var first = TextGroundingTest.evidence("first", firstFact, firstFact);
    var second = TextGroundingTest.evidence("second", secondFact, secondFact);
    var result =
        grounding.verify(
            "海山计划的预算和星港计划的预算分别是多少？",
            List.of(first, second),
            List.of(
                new GroundingQuote(first.physicalSegmentId(), firstFact),
                new GroundingQuote(second.physicalSegmentId(), secondFact)));
    assertTrue(result.supported());
    assertEquals(2, result.quotes().size());
  }

  @Test
  void directContradictionsWithinOneSourceCannotBeSelectedAway() {
    String page = "项目预算为1200万元。项目预算为1300万元。";
    var source = TextGroundingTest.evidence("same-document", page, page);
    var result =
        grounding.verify(
            "项目预算是多少？",
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), "项目预算为1200万元")));
    assertFalse(result.supported());
    assertEquals("conflicting_evidence", result.reason());
  }
}
