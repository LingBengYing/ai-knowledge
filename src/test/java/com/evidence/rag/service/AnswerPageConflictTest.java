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

/** Real parsing and authority hydration preserve context outside the sole retrieved chunk. */
class AnswerPageConflictTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "{0}, contextFirst={5}")
  @MethodSource("pageCases")
  void aConflictingUnretrievedChunkCannotReleaseAnAnswerOrCitation(
      String label,
      String question,
      String assertion,
      String context,
      boolean conflict,
      boolean contextFirst) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      String first = contextFirst ? context : assertion;
      String last = contextFirst ? assertion : context;
      String document =
          fixture.publish(
              "page-conflict.txt", first + "。\n" + "资料索引。\n".repeat(240) + last + "。\n");
      var selection = DocumentSelection.selected(List.of(document));
      var scope = fixture.evidence.snapshot(fixture.owner, selection, fixture.target);
      fixture.projection.results =
          candidates ->
              candidates.stream()
                  .filter(
                      candidate ->
                          fixture
                              .evidence
                              .hydrate(scope, List.of(candidate.segmentId()))
                              .getFirst()
                              .segment()
                              .text()
                              .contains(assertion))
                  .limit(1)
                  .toList();
      fixture.models.extraction =
          values ->
              new TextModels.Extraction(
                  List.of(new TextModels.Quote(values.getFirst().id(), assertion)), false);

      var result = fixture.answers.answer(fixture.owner, new AnswerCommand(question, selection));

      assertEquals(1, fixture.models.lastEvidence.size());
      var retrieved = fixture.models.lastEvidence.getFirst();
      assertTrue(retrieved.text().contains(assertion));
      assertFalse(
          retrieved.text().contains(context), "The context must not share the retrieved chunk");
      assertTrue(
          fixture
              .evidence
              .hydrate(scope, List.of(retrieved.id()))
              .getFirst()
              .page()
              .text()
              .contains(context));
      assertEquals(List.of("embed", "rerank", "extract"), fixture.models.calls);
      assertEquals(conflict ? "abstained" : "answered", result.status());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM query_traces WHERE policy_revision='"
                  + TextGrounding.VERSION
                  + "'"));
      if (conflict) {
        assertEquals("conflicting_evidence", result.reason());
        assertTrue(result.citations().isEmpty());
        assertFalse(result.answer().contains(assertion));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
        assertEquals(
            0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NOT NULL"));
      } else {
        assertEquals(assertion, result.answer());
        var citation = result.citations().getFirst();
        assertEquals(
            citation,
            fixture.answers.source(fixture.owner, result.answerId(), citation.number()).citation());
      }
    }
  }

  static Stream<Arguments> pageCases() {
    return Stream.of(
            new PageCase(
                "zh contradictory", "星港计划的预算是多少？", "星港计划的预算为1200万元", "星港计划的预算为1300万元", true),
            new PageCase(
                "zh different subject", "星港计划的预算是多少？", "星港计划的预算为1200万元", "新星港计划的预算为1300万元", false),
            new PageCase(
                "en contradictory",
                "What is Atlas's budget?",
                "Atlas's budget is 1200 USD",
                "Atlas's budget is 1300 USD",
                true),
            new PageCase(
                "en different subject",
                "What is Atlas's budget?",
                "Atlas's budget is 1200 USD",
                "Atlas Pro's budget is 1300 USD",
                false))
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
