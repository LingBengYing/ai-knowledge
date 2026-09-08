package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.tool.answer.TextGrounding;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real parsing/publication verifies that chunking cannot erase a named fact's conditions. */
class AnswerSemanticScopeTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "{0}")
  @MethodSource("conditions")
  void retainsConditionsAcrossRealChunksWithoutMixingNamedSubjects(
      String label, String question, String assertion, String condition, boolean sameSubject) {
    verifyConditionOrder(label, question, assertion, condition, sameSubject, false);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("conditions")
  void retainsEarlierConditionsAcrossRealChunksWithoutMixingNamedSubjects(
      String label, String question, String assertion, String condition, boolean sameSubject) {
    verifyConditionOrder(label, question, assertion, condition, sameSubject, true);
  }

  private void verifyConditionOrder(
      String label,
      String question,
      String assertion,
      String condition,
      boolean sameSubject,
      boolean conditionFirst) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      String first = conditionFirst ? condition : assertion;
      String last = conditionFirst ? assertion : condition;
      String document =
          fixture.publish("scope.txt", first + "。\n" + "资料索引。\n".repeat(240) + last + "。\n");
      fixture.models.extraction =
          values -> {
            var source =
                values.stream()
                    .filter(value -> value.text().contains(assertion))
                    .findFirst()
                    .orElseThrow();
            return new TextModels.Extraction(
                List.of(new TextModels.Quote(source.id(), assertion)), false);
          };

      var result =
          fixture.answers.answer(
              fixture.owner,
              new AnswerCommand(question, DocumentSelection.selected(List.of(document))));

      assertTrue(fixture.models.lastEvidence.size() > 1, "Fixture must cross parser chunks");
      assertTrue(
          fixture.models.lastEvidence.stream().anyMatch(value -> value.text().contains(condition)),
          "The restricting field must survive real parsing and hydration");
      assertFalse(
          fixture.models.lastEvidence.stream()
              .anyMatch(
                  value -> value.text().contains(assertion) && value.text().contains(condition)),
          "The qualifier may not accidentally share the quoted chunk");
      assertEquals(List.of("embed", "rerank", "extract"), fixture.models.calls);
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM query_traces WHERE policy_revision='"
                  + TextGrounding.VERSION
                  + "'"));
      assertEquals(sameSubject ? "abstained" : "answered", result.status(), label);
      if (sameSubject) {
        assertEquals("unsafe_evidence", result.reason());
        assertTrue(result.citations().isEmpty());
        assertFalse(result.answer().contains(assertion));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
        assertEquals(
            0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NOT NULL"));
      } else {
        assertEquals(assertion, result.answer());
        assertEquals(1, result.citations().size());
        var citation = result.citations().getFirst();
        assertEquals(assertion, citation.quote());
        assertEquals(
            citation,
            fixture.answers.source(fixture.owner, result.answerId(), citation.number()).citation());
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      }
    }
  }

  static Stream<Arguments> conditions() {
    return Stream.of(
        Arguments.of("zh same subject", "星港计划的预算是多少？", "星港计划的预算为1200万元", "星港计划的预算仅适用于试运行", true),
        Arguments.of(
            "zh distinct subject", "星港计划的预算是多少？", "星港计划的预算为1200万元", "新星港计划的预算仅适用于试运行", false),
        Arguments.of(
            "en same subject",
            "What is Atlas's budget?",
            "Atlas's budget is 1200 USD",
            "Atlas's budget applies only during the trial",
            true),
        Arguments.of(
            "en distinct subject",
            "What is Atlas's budget?",
            "Atlas's budget is 1200 USD",
            "Atlas Pro's budget applies only during the trial",
            false),
        Arguments.of(
            "zh same subject approval", "星港计划的预算是多少？", "星港计划的预算为1200万元", "星港计划的预算审批通过后才生效", true),
        Arguments.of(
            "zh distinct subject approval",
            "星港计划的预算是多少？",
            "星港计划的预算为1200万元",
            "新星港计划的预算审批通过后才生效",
            false),
        Arguments.of(
            "en same subject approval",
            "What is Atlas's budget?",
            "Atlas's budget is 1200 USD",
            "Atlas's budget is subject to approval",
            true),
        Arguments.of(
            "en distinct subject approval",
            "What is Atlas's budget?",
            "Atlas's budget is 1200 USD",
            "Atlas Pro's budget is subject to approval",
            false));
  }
}
