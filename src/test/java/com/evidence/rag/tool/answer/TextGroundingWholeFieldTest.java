package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextGroundingWholeFieldTest {
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @MethodSource("selfQualifiedFields")
  void aCompleteOriginalFieldStillCannotProveARefutedOrConditionalAssertion(
      String question, String field) {
    var source = TextGroundingTest.evidence("qualified", field, field);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), field)));
    assertFalse(result.supported(), field);
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> selfQualifiedFields() {
    return Stream.of(
        Arguments.of("项目预算是多少？", "项目预算为1200万元的说法不成立"),
        Arguments.of("项目预算是多少？", "项目预算为1200万元并不属实"),
        Arguments.of("项目预算是多少？", "项目预算为1200万元（错误）"),
        Arguments.of("项目预算是多少？", "项目预算为1200万元（存疑）"),
        Arguments.of("项目名称是什么？", "项目名称是星河只是误传"),
        Arguments.of("设备编号是什么？", "设备编号是ZX-714（尚待核实）"),
        Arguments.of("项目交付日期是什么？", "项目交付日期是2027年8月1日的消息未经核实"),
        Arguments.of("项目预算是多少？", "项目预算为1200万元仅在审批通过后有效"),
        Arguments.of("项目预算是多少？", "项目预算为1200万元只在许可后生效"),
        Arguments.of("What is Project A's budget?", "Project A's budget is 270 USD (incorrect)"),
        Arguments.of(
            "What is Project A's budget?", "Project A's budget is 270 USD only if approved"),
        Arguments.of(
            "What is Project A's budget?",
            "Project A's budget is 270 USD according to an unverified rumor"));
  }

  @ParameterizedTest
  @MethodSource("negativeNamedValues")
  void aNegativeStateOrAWordInsideAProperNameIsNotARefutation(String question, String field) {
    var source = TextGroundingTest.evidence("literal", field, field);
    assertTrue(
        grounding
            .verify(
                question,
                List.of(source),
                List.of(new GroundingQuote(source.physicalSegmentId(), field)))
            .supported(),
        field);
  }

  static Stream<Arguments> negativeNamedValues() {
    return Stream.of(
        Arguments.of("项目状态是什么？", "项目状态是未批准"),
        Arguments.of("项目状态是什么？", "项目状态是关闭"),
        Arguments.of("设备型号是什么？", "设备型号是NOT-READY-X"),
        Arguments.of("项目名称是什么？", "项目名称是不成立计划"),
        Arguments.of("项目名称是什么？", "项目名称是不夜城"),
        Arguments.of("What is Project A's status?", "Project A's status is inactive"));
  }
}
