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

class TextGroundingExampleTest {
  private final TextGrounding grounding = new TextGrounding();

  @ParameterizedTest
  @MethodSource("illustrations")
  void anExplicitIllustrationCannotSupportAnUnconditionalBudget(
      String question, String page, String quote) {
    var source = TextGroundingTest.evidence("illustration", page, page);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), quote)));

    assertFalse(result.supported(), "An illustration is not an asserted project budget");
    assertEquals("unsafe_evidence", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> illustrations() {
    return Stream.of(
        Arguments.of(
            "What is the project budget?",
            "Example: The project budget is 470 USD.",
            "Example: The project budget is 470 USD"),
        Arguments.of("项目预算是多少？", "例子：项目预算为470万元。", "例子：项目预算为470万元"),
        Arguments.of(
            "What is the project budget?",
            "EXAMPLE：The project budget is 470 USD.",
            "EXAMPLE：The project budget is 470 USD"),
        Arguments.of(
            "What is the project budget?",
            "eXaMpLe : The project budget is 470 USD.",
            "eXaMpLe : The project budget is 470 USD"),
        Arguments.of("项目预算是多少？", "例子: 项目预算为470万元。", "例子: 项目预算为470万元"));
  }

  @ParameterizedTest
  @MethodSource("illustrationBodies")
  void quotingOnlyTheBodyCannotRemoveItsIllustrationContext(
      String question, String page, String body, String expectedReason) {
    var source = TextGroundingTest.evidence("illustration-body", page, page);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), body)));

    assertFalse(result.supported());
    assertEquals(expectedReason, result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<Arguments> illustrationBodies() {
    return Stream.of(
        Arguments.of(
            "What is the project budget?",
            "Example: The project budget is 470 USD.",
            "The project budget is 470 USD",
            "incomplete_evidence"),
        Arguments.of("项目预算是多少？", "例子：项目预算为470万元。", "项目预算为470万元", "incomplete_evidence"),
        Arguments.of(
            "What is the project budget?",
            "Example: costs, The project budget is 470 USD.",
            "The project budget is 470 USD",
            "unsafe_evidence"),
        Arguments.of("项目预算是多少？", "例子：费用，项目预算为470万元。", "项目预算为470万元", "unsafe_evidence"),
        Arguments.of("项目预算是多少？", "例子：项目编号为B-7同时项目预算为470万元。", "项目预算为470万元", "unsafe_evidence"),
        Arguments.of(
            "What is the project budget?",
            "Introduction. Example: costs, The project budget is 470 USD.",
            "The project budget is 470 USD",
            "unsafe_evidence"));
  }

  @ParameterizedTest
  @MethodSource("assertionsBesideIllustrations")
  void aSeparateIllustrationDoesNotCreateAConflictWithAnAssertedBudget(
      String question,
      String assertion,
      String illustration,
      boolean illustrationFirst,
      String separator) {
    String prefix = illustrationFirst ? illustration + separator : "";
    String page = illustrationFirst ? prefix + assertion : assertion + separator + illustration;
    var source = TextGroundingTest.evidence("separate-illustration", page, assertion);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), assertion)));

    assertTrue(result.supported());
    assertEquals("supported", result.reason());
    assertEquals(1, result.quotes().size());
    var quote = result.quotes().getFirst();
    assertEquals(source.physicalSegmentId(), quote.physicalId());
    assertEquals(assertion, quote.quote());
    assertEquals(prefix.codePointCount(0, prefix.length()), quote.start());
    assertEquals(
        (prefix + assertion).codePointCount(0, (prefix + assertion).length()), quote.end());
  }

  static Stream<Arguments> assertionsBesideIllustrations() {
    return Stream.of(false, true)
        .flatMap(
            first ->
                Stream.of(
                    Arguments.of(
                        "What is the project budget?",
                        "The project budget is 470 USD",
                        "Example: The project budget is 990 USD",
                        first,
                        "。\n"),
                    Arguments.of("项目预算是多少？", "项目预算为470万元", "例子：项目预算为990万元", first, "。\n"),
                    Arguments.of(
                        "What is the project budget?",
                        "The project budget is 470 USD",
                        "Example: The project budget is 990 USD",
                        first,
                        ". "),
                    Arguments.of("项目预算是多少？", "项目预算为470万元", "例子：项目预算为990万元", first, "。")));
  }

  @ParameterizedTest
  @MethodSource("ordinaryTitles")
  void ordinaryTitlesAndNamesContainingExampleDoNotInvalidateAnAssertedBudget(
      String question, String field) {
    var source = TextGroundingTest.evidence("ordinary-title", field, field);
    var result =
        grounding.verify(
            question,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), field)));

    assertTrue(
        result.supported(), "A title or proper name does not make an asserted fact hypothetical");
    assertEquals("supported", result.reason());
    assertEquals(1, result.quotes().size());
    var quote = result.quotes().getFirst();
    assertEquals(source.physicalSegmentId(), quote.physicalId());
    assertEquals(field, quote.quote());
    assertEquals(0, quote.start());
    assertEquals(field.codePointCount(0, field.length()), quote.end());
    assertEquals(1, quote.factHashes().size());
    assertTrue(quote.factHashes().getFirst().matches("[a-f0-9]{64}"));
  }

  static Stream<Arguments> ordinaryTitles() {
    return Stream.of(
        Arguments.of("What is the project budget?", "Summary: The project budget is 470 USD"),
        Arguments.of("项目预算是多少？", "说明：项目预算为470万元"),
        Arguments.of(
            "What is the project budget?", "Example Holdings: The project budget is 470 USD"),
        Arguments.of("项目预算是多少？", "example计划：项目预算为470万元"));
  }
}
