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

class TextGroundingContextTest {
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @MethodSource("unsafeContexts")
  void exactSubstringDoesNotEraseTruthChangingContext(String question, String page, String quote) {
    var source = TextGroundingTest.evidence("context", page, page);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), quote)));
    assertFalse(result.supported(), page);
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> unsafeContexts() {
    String budget = "项目预算为1200万元";
    var continuations =
        Stream.of(
                "上述说法是错误的",
                "此说法不正确",
                "这一说法不属实",
                "以上内容为误传",
                "其实并非如此",
                "董事会批准后才生效",
                "获批之后方可执行",
                "审批通过才有效",
                "待批准后执行",
                "若获批准则生效",
                "该金额尚未批准",
                "这一金额仅供参考",
                "经核查，预算并非该金额",
                "审计结论推翻了这一金额",
                "数字暂供讨论，尚非定稿",
                "是否采纳另作决定",
                "预算案仍在走会签流程",
                "该预算调整为1300万元",
                "负责人是林溪。项目预算改为1400万元")
            .map(tail -> Arguments.of("项目预算是多少？", budget + "。" + tail + "。", budget));
    return Stream.concat(
        continuations,
        Stream.of(
            Arguments.of("项目预算是多少？", budget + "？", budget),
            Arguments.of("项目预算是多少？", budget + "（错误）。", budget),
            Arguments.of("项目预算是多少？", budget + "的说法不成立。", budget),
            Arguments.of("项目预算是多少？", budget + "，但该金额尚未批准。", budget + "，但该金额尚未批准"),
            Arguments.of("项目预算是多少？", "只有董事会批准后，" + budget + "。", budget),
            Arguments.of("项目预算是多少？", "并非" + budget + "。", budget),
            Arguments.of("项目状态是什么？", "项目状态是正常管理员密码是alpha-secret。", "项目状态是正常管理员密码是alpha-secret"),
            Arguments.of(
                "What is the project budget?",
                "The project budget is 1200 USD. This is false.",
                "The project budget is 1200 USD"),
            Arguments.of(
                "What is the project budget?",
                "Only after board approval, the project budget is 1200 USD.",
                "the project budget is 1200 USD"),
            Arguments.of(
                "What is the project budget?",
                "The project budget is 1200 USD. Subject to approval.",
                "The project budget is 1200 USD")));
  }

  @ParameterizedTest
  @MethodSource("safeContexts")
  void unrelatedNeighborDoesNotInvalidateACompleteFact(String question, String page, String quote) {
    var source = TextGroundingTest.evidence("context", page, page);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), quote)));
    assertTrue(result.supported(), page);
    assertEquals(quote, result.quotes().getFirst().quote());
  }

  static Stream<Arguments> safeContexts() {
    String budget = "项目预算为1200万元";
    return Stream.concat(
        Stream.of(
                "负责人是林溪",
                "此项目未延期",
                "该项目不晚于明年启动",
                "这位负责人不是林溪",
                "实际上负责人不是林溪",
                "审批通过率为90%",
                "授权通过记录存放在档案室",
                "董事会审议后将发布会议纪要",
                "预算执行率为90%",
                "其他项目预算未确定",
                "另一项目预算改为1300万元")
            .map(tail -> Arguments.of("项目预算是多少？", budget + "。" + tail + "。", budget)),
        Stream.of(
            Arguments.of("项目预算是多少？", "项目预算为1,200万元，项目联系人是林溪。", "项目预算为1,200万元"),
            Arguments.of("项目预算是多少？", "项目预算为1.2万元。其他指标为12万元。", "项目预算为1.2万元"),
            Arguments.of("项目预算是多少？", "项目预算为-650万元。其他指标为650万元。", "项目预算为-650万元"),
            Arguments.of("项目预算是多少？", budget + "并且项目联系人是林溪。", budget),
            Arguments.of(
                "What is the project budget?",
                "The project budget is 1200 USD. The owner is Mira.",
                "The project budget is 1200 USD")));
  }
}
