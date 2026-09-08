package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real parser, publication, authority, answer and source paths; models remain explicit stand-ins.
 */
class AnswerSemanticContextTest {
  @TempDir Path directory;

  @ParameterizedTest
  @MethodSource("labels")
  void exampleContextCannotReleaseAnAnswerButOrdinaryTitlesRetainTheirSources(
      String question, String statement, boolean example) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      String document = fixture.publish("label.txt", statement);
      var result =
          fixture.answers.answer(
              fixture.owner,
              new AnswerCommand(question, DocumentSelection.selected(List.of(document))));
      assertEquals(List.of("embed", "rerank", "extract"), fixture.models.calls);
      verifyTrace(fixture, result, !example);
      if (example) {
        assertEquals("unsafe_evidence", result.reason());
      } else {
        assertEquals(statement, result.answer());
      }
    }
  }

  static Stream<Arguments> labels() {
    return Stream.of(
        Arguments.of("项目预算是多少？", "例子：项目预算为470万元", true),
        Arguments.of("What is the project budget?", "Example: The project budget is 470 USD", true),
        Arguments.of("项目预算是多少？", "说明：项目预算为470万元", false),
        Arguments.of(
            "What is the project budget?",
            "Example Holdings: The project budget is 470 USD",
            false));
  }

  @ParameterizedTest
  @MethodSource("procedures")
  void completeProcedureAndPartialExtractionHaveDifferentAuditedOutcomes(
      String question, String first, String last, String separator, boolean complete) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      String procedure = first + separator + last;
      String document = fixture.publish("procedure.txt", procedure);
      fixture.models.extraction =
          values ->
              new TextModels.Extraction(
                  List.of(
                      new TextModels.Quote(values.getFirst().id(), complete ? procedure : first)),
                  false);
      var result =
          fixture.answers.answer(
              fixture.owner,
              new AnswerCommand(question, DocumentSelection.selected(List.of(document))));
      verifyTrace(fixture, result, complete);
      if (complete) {
        assertEquals(procedure, result.answer());
        assertEquals(procedure, result.citations().getFirst().quote());
      } else {
        assertEquals("incomplete_evidence", result.reason());
      }
    }
  }

  static Stream<Arguments> procedures() {
    return Stream.of(false, true)
        .flatMap(
            complete ->
                Stream.of(
                    Arguments.of("如何清理日志？", "清理日志：进入设置", "然后点击清理按钮", "。", complete),
                    Arguments.of(
                        "How do I reset the device?",
                        "Reset the device: press the service button",
                        "Then wait for the green light before releasing it",
                        ". ",
                        complete)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completeStepsMaySpanRealChunksButUnretrievedStepsNeverBecomeCitations(boolean complete) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      String first = "清理日志：进入设置并输入校验串" + "甲".repeat(980);
      String second = "然后点击清理按钮并输入校验串" + "乙".repeat(980);
      String last = "最后等待绿灯亮起后松开按钮";
      var steps = List.of(first, second, last);
      String document = fixture.publish("long-procedure.txt", String.join("。", steps) + "。");
      var selection = DocumentSelection.selected(List.of(document));
      var scope = fixture.evidence.snapshot(fixture.owner, selection, fixture.target);
      if (!complete) {
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
                                .contains(first))
                    .limit(1)
                    .toList();
      }
      fixture.models.extraction =
          values ->
              new TextModels.Extraction(
                  values.stream()
                      .flatMap(
                          value ->
                              steps.stream()
                                  .filter(value.text()::contains)
                                  .map(step -> new TextModels.Quote(value.id(), step)))
                      .toList(),
                  false);
      var result = fixture.answers.answer(fixture.owner, new AnswerCommand("如何清理日志？", selection));
      verifyTrace(fixture, result, complete);
      if (complete) {
        assertTrue(
            fixture.models.lastEvidence.size() > 1, "Exercise actual parser chunk boundaries");
        assertEquals(steps, result.citations().stream().map(value -> value.quote()).toList());
        steps.forEach(step -> assertTrue(result.answer().contains(step)));
      } else {
        assertEquals(1, fixture.models.lastEvidence.size());
        assertFalse(fixture.models.lastEvidence.getFirst().text().contains(second));
        assertFalse(fixture.models.lastEvidence.getFirst().text().contains(last));
        assertEquals("incomplete_evidence", result.reason());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("procedureQualifiers")
  void procedureQualifiersRemainVisibleInTheAnswerOrCauseAnAuditedRefusal(
      String question,
      String first,
      String continuation,
      String separator,
      boolean complete,
      String expectedReason) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 2)) {
      String page = first + separator + continuation;
      String document = fixture.publish("procedure-qualifier.txt", page);
      fixture.models.extraction =
          values ->
              new TextModels.Extraction(
                  List.of(new TextModels.Quote(values.getFirst().id(), complete ? page : first)),
                  false);
      var result =
          fixture.answers.answer(
              fixture.owner,
              new AnswerCommand(question, DocumentSelection.selected(List.of(document))));
      verifyTrace(fixture, result, expectedReason == null);
      if (expectedReason == null) {
        assertEquals(page, result.answer());
        assertEquals(page, result.citations().getFirst().quote());
      } else {
        assertEquals(expectedReason, result.reason());
      }
    }
  }

  static Stream<Arguments> procedureQualifiers() {
    return Stream.concat(
        Stream.of(
            Arguments.of("如何清理日志？", "清理日志：进入设置", "然后点击清理按钮（错误）", "。", true, "unsafe_evidence"),
            Arguments.of(
                "How do I reset the device?",
                "Reset the device: press the service button",
                "Then release it (incorrect)",
                ". ",
                true,
                "unsafe_evidence")),
        Stream.of(false, true)
            .flatMap(
                complete ->
                    Stream.of(
                        Arguments.of(
                            "如何清理日志？",
                            "清理日志：进入设置",
                            "管理员授权是清理日志的前提",
                            "。",
                            complete,
                            complete ? null : "incomplete_evidence"),
                        Arguments.of(
                            "How do I reset the device?",
                            "Reset the device: press the service button",
                            "Administrator approval is required to reset the device",
                            ". ",
                            complete,
                            complete ? null : "incomplete_evidence"))));
  }

  private static void verifyTrace(
      AnswerTestContext fixture, AnswerResult result, boolean answered) {
    assertEquals(answered ? "answered" : "abstained", result.status());
    assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
    assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
    assertEquals(
        1,
        fixture.scalar(
            "SELECT COUNT(*) FROM query_traces WHERE policy_revision='"
                + TextGrounding.VERSION
                + "'"));
    if (answered) {
      assertFalse(result.citations().isEmpty());
      assertEquals(
          result.citations().size(), fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      result
          .citations()
          .forEach(
              citation -> {
                assertEquals(
                    citation,
                    fixture
                        .answers
                        .source(fixture.owner, result.answerId(), citation.number())
                        .citation());
                assertEquals(
                    citation.quote().codePointCount(0, citation.quote().length()),
                    citation.end() - citation.start());
                assertTrue(
                    fixture.models.lastEvidence.stream()
                        .anyMatch(value -> value.text().contains(citation.quote())));
              });
    } else {
      assertTrue(result.citations().isEmpty());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(
          0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NOT NULL"));
    }
  }
}
