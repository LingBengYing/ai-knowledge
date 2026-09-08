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

class TextGroundingBooleanTest {
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @MethodSource("booleanFacts")
  void preservesTheExactPositiveOrNegativeAssertion(String question, String fact) {
    var source = TextGroundingTest.evidence("boolean", fact + "。", fact + "。");
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), fact)));
    assertTrue(result.supported(), fact);
    assertEquals(fact, result.quotes().getFirst().quote());
  }

  static Stream<Arguments> booleanFacts() {
    return Stream.of(
        Arguments.of("是否允许用户导出报告？", "用户允许导出报告"),
        Arguments.of("用户是否允许导出报告？", "用户不允许导出报告"),
        Arguments.of("是否允许导出报告？", "用户允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "用户从未被允许导出报告"),
        Arguments.of("管理员密码能否共享？", "管理员密码不允许共享"),
        Arguments.of("星港系统是否支持离线导出？", "星港系统支持离线导出"),
        Arguments.of("星港系统是否支持离线导出？", "星港系统不支持离线导出"),
        Arguments.of("Are users allowed to export reports?", "Users are allowed to export reports"),
        Arguments.of(
            "Are users allowed to export reports?", "Users are not allowed to export reports"),
        Arguments.of("Does Atlas support offline export?", "Atlas supports offline export"),
        Arguments.of(
            "Does Atlas support offline export?", "Atlas does not support offline export"));
  }

  @ParameterizedTest
  @MethodSource("unsafePermissions")
  void cannotDropNegationConditionSubjectOrLaterRevocation(
      String question, String page, String quote) {
    var source = TextGroundingTest.evidence("boolean", page, page);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), quote)));
    assertFalse(result.supported(), page);
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> unsafePermissions() {
    return Stream.of(
        Arguments.of("是否允许用户导出报告？", "用户不允许导出报告。", "允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "用户从未被允许导出报告。", "允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "只有审批通过后，用户允许导出报告。", "用户允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "用户仅在审批后允许导出报告。", "用户仅在审批后允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "用户允许导出报告。实际并未开放此权限。", "用户允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "用户允许导出报告。该权限已撤销。", "用户允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "管理员允许导出报告。", "管理员允许导出报告"),
        Arguments.of("是否允许用户导出报告？", "用户不是不允许导出报告。", "用户不是不允许导出报告"),
        Arguments.of(
            "Are users allowed to export reports?",
            "Users are not allowed to export reports.",
            "allowed to export reports"),
        Arguments.of(
            "Are users allowed to export reports?",
            "Only after approval, users are allowed to export reports.",
            "users are allowed to export reports"),
        Arguments.of(
            "Does Atlas support offline export?",
            "New Atlas supports offline export.",
            "New Atlas supports offline export"));
  }
}
