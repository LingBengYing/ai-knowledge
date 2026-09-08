package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** A retrieved chunk must not hide contradictory facts in its authoritative page context. */
class TextGroundingPageConflictTest {
  @ParameterizedTest(name = "{0}, contextFirst={5}")
  @MethodSource("pageCases")
  void checksUnretrievedPageFieldsWithoutMixingSubjectsOrTreatingExamplesAsFacts(
      String label,
      String question,
      String assertion,
      String context,
      boolean conflict,
      boolean contextFirst) {
    String page = contextFirst ? context + "。\n" + assertion : assertion + "。\n" + context;
    var source = TextGroundingTest.evidence("page-conflict", page, assertion);
    var result =
        new TextGrounding()
            .verify(
                question,
                List.of(source),
                List.of(new GroundingQuote(source.physicalSegmentId(), assertion)));
    assertEquals(!conflict, result.supported());
    assertEquals(conflict ? "conflicting_evidence" : "supported", result.reason());
    if (conflict) {
      assertTrue(result.quotes().isEmpty());
    } else {
      assertEquals(
          List.of(assertion), result.quotes().stream().map(quote -> quote.quote()).toList());
    }
  }

  static Stream<Arguments> pageCases() {
    return Stream.of(
            new PageCase("zh contradictory", "项目预算是多少？", "项目预算为1200万元", "项目预算为1300万元", true),
            new PageCase(
                "en contradictory",
                "What is Atlas's budget?",
                "Atlas's budget is 1200 USD",
                "Atlas's budget is 1300 USD",
                true),
            new PageCase(
                "zh distinct subject", "星港计划的预算是多少？", "星港计划的预算为1200万元", "新星港计划的预算为1300万元", false),
            new PageCase(
                "en distinct subject",
                "What is Atlas's budget?",
                "Atlas's budget is 1200 USD",
                "Atlas Pro's budget is 1300 USD",
                false),
            new PageCase("numeric grouping", "项目预算是多少？", "项目预算为1200万元", "项目预算为1,200万元", false),
            new PageCase("explicit example", "项目预算是多少？", "项目预算为1200万元", "示例：项目预算为1300万元", false),
            new PageCase("zh draft", "项目预算是多少？", "项目预算为1200万元", "草案：项目预算为1300万元", false),
            new PageCase(
                "en draft",
                "What is Atlas's budget?",
                "Atlas's budget is 1200 USD",
                "Draft: Atlas's budget is 1300 USD",
                false),
            new PageCase("sign preserved", "项目预算是多少？", "项目预算为1200万元", "项目预算为-1200万元", true),
            new PageCase("decimal preserved", "项目预算是多少？", "项目预算为1200万元", "项目预算为1200.5万元", true),
            new PageCase(
                "unit preserved",
                "What is Atlas's budget?",
                "Atlas's budget is 1200 USD",
                "Atlas's budget is 1200 EUR",
                true))
        .flatMap(
            item ->
                Stream.of(false, true)
                    .map(
                        first ->
                            Arguments.of(
                                item.label(),
                                item.question(),
                                item.assertion(),
                                item.context(),
                                item.conflict(),
                                first)));
  }

  private record PageCase(
      String label, String question, String assertion, String context, boolean conflict) {}
}
